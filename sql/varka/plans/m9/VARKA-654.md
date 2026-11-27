# VARKA-654: The census and the cast matrix by milestone, with TIMESTAMP_LTZ

*Planned on 27 November 2026, and revised the same day after a builder's review and after
row 656's plan was read. The plan stands alone: an agent needs this file and the
repository's `AGENTS.md` files. The row is not started: milestone 9 waits for the owner's word
(`m9/PLAN.md` decision 18), and this row also waits for milestone 8's close (decision 11). The
code facts were checked on master `a0e3a62cf2b`, which holds the upstream sync of row 663.
Every count below that is called a prediction was computed on that commit, by the changes of
section 4 applied to a scratch copy of the script and its inputs; the build re-derives each by
the command beside it, since milestone 8's census moves until its close.*

## 1. What this row is

**The row's text** (`m9/PLAN.md` section 3): "The census and the cast matrix by milestone, with
`TIMESTAMP_LTZ`." Its done-when: "the summary for milestone 9 prints the pairs admitted and
fused for each type, and every reader of the census and cast files reads the milestone's; `ltz`
is a census type and each of the 18 entries that come into scope has a row or a reason; the cast
table holds the 23 pairs and `VarkaCastMatrixSuite` passes; and milestone 8's census files are as
its close left them".

In plain words. Today one tool, `dev/varka_census.py`, writes milestone 8's census and cast
files, and thirteen other files name those files by fixed paths. This row gives milestone 9
files of its own, moves every reader to them, freezes milestone 8's files at its close, and
then brings the type `TIMESTAMP` with local time zone into milestone 9's census and cast matrix
under the census name `ltz`. No kernel is built and no emitted byte moves.

**What must be merged before it starts.**

| what | why | how the builder checks it |
| :--- | :--- | :--- |
| this plan on master | the build cites it and writes its outcome into it | `test -f sql/varka/plans/m9/VARKA-654.md && echo merged` prints `merged`. The lead merges the plan before part a starts |
| milestone 8's close | decision 11: "The rows that bring a new type into the census, 654 and 656, wait for milestone 8's close" | after the fetch of 5.1, `git tag -l varka-m8` prints `varka-m8` |
| row 663, the upstream sync | the census must hold apache/spark's names of the sync's day | merged as #1773; `git merge-base --is-ancestor d1286ba2c16 HEAD; echo $?` prints `0` |

**What it gives the rows after it.**

| row | what it reads of this row | the name or file |
| :--- | :--- | :--- |
| 656, `TIMESTAMP_LTZ` on the long lane | the census name `ltz` on a still-declined type; `"ltz"` in `CAST_TESTS` with `castToTimestamp`; milestone 9's five files; the entries that admit `ltz` with no fused mark; the entry `ntz_minus_ntz` of class `SubtractTimestamps`; `"timestamp": "ltz"` in `SIMPLE_COLUMN_TYPES` (section 11 has each with its part) | `sql/varka/types.tsv`; `VarkaTypeConformance`; `sql/varka/plans/m9/`; `dev/varka_census.py` |
| 657, parts C and D | the lines of `hour`, `minute` and `second` with `ltz` admitted and not fused; the pair `ltz` to `date`, legal in the three modes, not fused | `sql/varka/plans/m9/census_curated.txt`; `sql/varka/plans/m9/casts.tsv` |
| 682 to 687, 689, 690 | nothing new: the command `python3 dev/varka_census.py --check`, which now reads milestone 9's files | `dev/varka_census.py` |
| 688, part c | the entry of `ts + dt` by its class | `grep -n 'TimestampAddInterval' sql/varka/plans/m9/census_curated.txt` prints one line |
| 667 to 671, 675 | the cells and the cast pairs that name each of them as the row that owes them | the `rows` fields of the curated file and of `casts.tsv` |
| 661, the nanosecond types | a milestone's type list in one place | `MILESTONES[9]` in `dev/varka_census.py` |
| 677, the close | the summary of milestone 9, and the way to close a milestone's census | `python3 dev/varka_census.py --summary`; the comment above `CURRENT` |
| 678 and 679 | nothing | (section 11) |

**The goals it serves** (`m9/PLAN.md` 1.1), each with the item of 1.2 it moves.

- **G1**, directly: "**G1. `TIMESTAMP_LTZ` at microseconds (phase 1).** It is a census type."
  Item 1 of 1.2: "at least three quarters of `TIMESTAMP_LTZ`'s (entry, type) pairs fused (about
  66 entries are expected to admit it)". This row sets that item's denominator. Check:
  `python3 dev/varka_census.py --summary | grep '^  ltz' | awk '{print $3, $5}'` prints the
  pairs fused and admitted, `0 77` on master `a0e3a62cf2b`.
- **The preamble of 1.2**, directly: "The census items are read from `dev/varka_census.py
  --summary`, which prints the pairs fused and admitted for each type; row 654 gives it this
  milestone's files." Check: `git ls-files sql/varka/plans/m9/census.tsv` prints the path.
- **G3**, directly for the casts: item 3 of 1.2 counts "the 42 legal pairs of `m8/casts.tsv`
  with a temporal side, plus `TIMESTAMP_LTZ`'s pairs row 654 adds (23)". Check:
  `awk -F'\t' '$1=="ltz"||$2=="ltz"' sql/varka/plans/m9/casts.tsv | wc -l` prints `23`.
- **Item 7 of 1.2**, directly: "Every pair left unfused carries its reason in the census: a
  declined band, an owner's decision, a function Spark itself rejects over the type, or a named
  row of the next milestone." Every `ltz` cell this row adds names the row of this milestone
  that owes it. Check: section 5.2, command 9.
- **G4**, only through row 661, which adds the nanosecond types to the list this row makes.
- **G5**, only through rows 658 and 659: a generated task plan's commands end in `python3
  dev/varka_census.py --check` (`m9/PLAN.md` 2.7), which must read milestone 9's files.
- **G2, G6 and G7** are not served: the row reads no zone, writes no post and times nothing.

A side effect on item 3 of 1.2 is stated in 4.10: the row adds 15 admitted pairs to the five
temporal types of milestone 8 and takes away 3 fused ones, so the item's "203 of 278 today, so
251" moves, as its preamble says the counts do.

## 2. What an agent must know

**The census.** `dev/varka_census.py` reads every name of Spark's function registry
(`FunctionRegistry.scala`) and writes one line a name into `census.tsv`. A name is *in scope*
when its inputs and its result can all be types of the census; any other name is *out*, with a
reason from the script's `NAME_EXCLUSIONS` (by name) or `SECTION_EXCLUSIONS` (by registry
section). The judgement is in the *curated file*, `census_curated.txt`: for each name in scope,
the types it admits, its result, the types Varka fuses today, the *need* (`done`, `plumbing`,
`semantics`, `ulp`) and the *rows*, the task rows that owe what is not fused. The script fails
when the registry and the curated file disagree, and when a type is claimed fused that no row
of the coverage table (`sql/varka/coverage.json`) shows.

**A census type** is a short name of a Spark type: `bool byte short int bigint float double dec
date ntz time ym dt`, thirteen today. The names and their order come from the type registry
(`VarkaTypes`, Java), through its committed table `sql/varka/types.tsv`, column `census`. A
**pair** is one (entry, type): an entry and one type it admits. The milestone's headline is the
number of pairs fused of pairs admitted, for each type, which `--summary` prints.

**The cast matrix.** `casts.tsv` has one line for each ordered pair of census types that
Spark's `Cast` allows: its legality under LEGACY, ANSI and TRY, what the cast does (`kind`,
`semantics`), how it is proven (`proof`), in which modes it fuses (`fused_today`), and its rows.
The legality is the script's function `legal()`, transcribed by hand from `Cast.canCast` and
`Cast.canAnsiCast`; the suite `VarkaCastMatrixSuite` asks `Cast` itself about every pair and
fails where the file disagrees. **The probe**, `casts_fused.tsv`, is written by the same suite:
for every legal cast between two *forms* of the types (a decimal's two precisions, a time's
three, each interval field range) it asks the compiler whether the cast fuses. **The suites'
record**, `casts_suites.tsv`, is written by Spark's three cast suites run under Varka in
sql/core. `fused_today` is derived from the probe, never typed.

**The support table** is a block of `docs/sql-varka.md` between the markers `<!-- BEGIN
generated support table -->` and `<!-- END generated support table -->`, which the script
writes from the census.

**The three commands.** `python3 dev/varka_census.py` rewrites `census.tsv`, `casts.tsv` and
the support table and prints the summary; `--summary` prints it and writes nothing; `--check`
runs the script's doctests and fails if a file differs from a fresh one. The check runs in the
pre-commit hook (`dev/varka_precommit.sh`), in the Build's lint job, in the push check and in
`dev/varka_regen_generated.sh`.

**By milestone** means: the five files above live in the folder of the milestone they count
(`sql/varka/plans/m<n>/`), the script knows a table of milestones, writes only the *current*
one's files, and treats a *closed* milestone's files as a record that it never rewrites. A
**reader** is any file other than the script that names one of the five files by a fixed path.

**`TIMESTAMP_LTZ`** is Spark's `TimestampType`, SQL `TIMESTAMP`: a long of microseconds since
the epoch in UTC. The registry declines it today, and no kernel reads it until row 656. Its
census name is `ltz`.

**The names this row reads.**

| name | where | meaning |
| :--- | :--- | :--- |
| `OUT`, `CURATED`, `CASTS_OUT`, `CASTS_FUSED`, `CASTS_SUITES` | `dev/varka_census.py` | the five paths, fixed to milestone 8's folder today |
| `ORDER` | `dev/varka_census.py` | the census types in the registry's order, read from `sql/varka/types.tsv` at import |
| `EXISTING`, `NEW` | `dev/varka_census.py` | milestone 8's split of the types: read by a lane before it, added by it |
| `NAME_EXCLUSIONS`, `LTZ`, `FOLDED` | `dev/varka_census.py` | the reasons by name; `LTZ` is the reason of 20 names |
| `legal`, `cast_kind`, `cast_proof`, `cast_rows`, `pair_credit`, `CAST_ALIASES` | `dev/varka_census.py` | the cast matrix's columns and the credit of a cast entry |
| `VarkaTypes.Info.census()` | catalyst, `codegen/varka/VarkaTypes.java` | a type's census name, null for a type outside the census |
| `VarkaCastMatrixSuite.TYPES` | catalyst tests, Java | every registry entry with a census name, with its forms |
| `VarkaTypeConformance.CAST_TESTS`, `castTo(String)` | catalyst tests, Java | one cast test for each census type as a target |
| `VarkaSuiteRecord.plans(String)` | sql/core tests, Java | the path of a cast file |

**The names this row adds.**

| name | part | where | meaning |
| :--- | :--- | :--- | :--- |
| `Milestone(number, types, new, closed)` with `path(name)` | a | `dev/varka_census.py` | one milestone's census: its types in order, the ones it adds, and its files' sha256 once closed |
| `FILES`, `MILESTONES`, `CURRENT`, `MILESTONE` | a | `dev/varka_census.py` | the five file names; the table of milestones 8 and 9; the current number, 9; its entry |
| `closed_errors(root, milestones)` | a | `dev/varka_census.py` | each file of a closed milestone that is not as its close left it |
| `READERS`, `READER_NAMES`, `CENSUS_FILES_JAVA`, `reader_errors(root, current, readers)` | a | `dev/varka_census.py` | each reader that names another milestone's files than the current one's |
| `M8_CLOSED` | a | `dev/varka_census.py` | the sha256 of milestone 8's five files at its close |
| `closed_summary(m, root)`, `closed_main(args)`, `--milestone N` | a | `dev/varka_census.py` | a closed milestone's summary from its files; its `--summary` and `--check` |
| `VarkaCensusFiles.MILESTONE`, `VarkaCensusFiles.of(String home, String name)` | a | catalyst tests, Java, `codegen/varka/VarkaCensusFiles.java` | the folder of the current milestone's files, and a file's path in it |
| `sql/varka/plans/m9/{census.tsv, census_curated.txt, casts.tsv, casts_fused.tsv, casts_suites.tsv}` | a | the plans folder | milestone 9's five files |
| the census name `ltz` | b | `VarkaTypes`, `sql/varka/types.tsv` | `TIMESTAMP` in the census and the cast matrix |
| `NANOS` | b | `dev/varka_census.py` | the reason of the two nanosecond names |
| `ltz_cast_kind(src, dst)`, `ZONE_PROOF`, `READ` | b | `dev/varka_census.py` | the kinds of `ltz`'s pairs; the proof of a pair that reads the zone; the census types a kernel reads |
| `castToTimestamp()` | b | `VarkaTypeConformance` | the cast test with `ltz` as the target |
| `timestampPairs()` | b | `VarkaCastMatrixSuite` | the 23 pairs, pinned |

## 3. The admission check

Each fact was checked on master `a0e3a62cf2b`, in `/home/max/proj/varka-m9-read-w`.

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 1 | The script names milestone 8's five files by fixed paths | `grep -c "plans/m8" dev/varka_census.py` | `5` |
| 2 | Nine more files name them by a path (the tenth line is the script) | `git grep -c -E "plans/m8/(census\|casts)" -- .github README.md dev docs sql/varka/.gitattributes` | `.github/workflows/varka-fuzz.yml:1`, `README.md:1`, `dev/varka_census.py:5`, `dev/varka_new_lane.py:1`, `dev/varka_nightly.sh:1`, `dev/varka_precommit.sh:1`, `dev/varka_regen_generated.sh:2`, `dev/varka_regen_inputs.py:3`, `docs/sql-varka.md:3`, `sql/varka/.gitattributes:4` |
| 3 | Three Java test classes and one tool build the path from its parts | `git grep -c '"plans", "m8"' -- sql dev` | `VarkaCastMatrixSuite.java:3`, `VarkaTypeConformance.java:1`, `VarkaSuiteRecord.java:1`, `dev/varka_generated_merge.py:1`, and three files that read `m8/results` or `m8/PLAN.md`, which are not census files: `VarkaDecimalScaleSuite.java:1`, `VarkaMathContractSuite.java:1`, `VarkaCoverageSuite.scala:2` |
| 4 | So the readers are thirteen, not the six of `m9/PLAN.md` 2.4 | facts 2 and 3 | 2.4 names `dev/varka_new_lane.py`, `dev/varka_regen_generated.sh`, `dev/varka_nightly.sh`, the fuzz workflow, `sql/varka/.gitattributes` and `VarkaCastMatrixSuite`; it misses `dev/varka_precommit.sh`, `dev/varka_regen_inputs.py`, `dev/varka_generated_merge.py`, `VarkaTypeConformance`, `VarkaSuiteRecord`, `docs/sql-varka.md` and `README.md` |
| 5 | 20 names carry the zoned reason | `grep -c '": LTZ' dev/varka_census.py`; `python3 dev/varka_census.py --summary \| grep "TIMESTAMP with local"` | `20`; three lines, counting 18, 1 and 1 |
| 6 | The census types and their order are the registry's table, and `TIMESTAMP` has no census name | `grep -n "^ORDER" dev/varka_census.py`; `grep -P "^TIMESTAMP\t" sql/varka/types.tsv` | `ORDER = [r["census"] for r in types_rows() if r["census"] != "-"]`; `TIMESTAMP	TimestampType	-	TIMESTAMP	declined	-	-	64	plain	-	-	-	-` |
| 7 | The group `ALL` of the curated file is `ORDER`, so every `ALL` entry admits a new type by itself | `grep -n '"ALL": ORDER' dev/varka_census.py` | one line, in `GROUPS` |
| 8 | An entry whose need is `done` must be fused for every type it admits | `grep -n "disagrees with fused" dev/varka_census.py` | two lines: the doctest and the rule in `row` |
| 9 | The suite's types are every registry entry with a census name | `grep -n "i.census() != null" sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCastMatrixSuite.java` | one line, in the static block that fills `TYPES` |
| 10 | Every conformance suite fails until each census type has a cast test | `grep -n "the cast tests are one per" sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTypeConformance.java` | one line, in `castTo`: `CAST_TARGETS.keySet()` must equal `CAST_TESTS` |
| 11 | A cast the registers owe cancels its conformance test; it does not fail it | `VarkaTypeConformance.run`: a form with an owner that does not compile joins `owed`, and "A check with an owed form is cancelled naming them" | read |
| 12 | An identity cast counts as fused with no coverage row | `grep -n 'return "all"' dev/varka_census.py` | the first of the lines, in `pair_credit`, under `cast_kind(src, dst)[0] == "identity"` |
| 13 | A type fused anywhere in the committed census needs a conformance suite or an owner in the registry | `grep -n "names no conformance" dev/varka_census.py` | one line, in `conformance_errors` |
| 14 | Two places hold the number of types as a literal | `grep -n '\* 14))\|over the 13 types' dev/varka_census.py` | `cast_tables` (`[":---"] * 14`) and `cast_summary` |
| 15 | Spark's legality of `TIMESTAMP` with the thirteen types | `Cast.canCast`, `Cast.canAnsiCast`, `Cast.canTryCast` in `Cast.scala` | 23 pairs, 4.8 |
| 16 | The record of Spark's cast suites spells a `TIMESTAMP` target as `-` today, and no check lifts a `TIMESTAMP` source | `awk -F'\t' '$5=="TIMESTAMP"' sql/varka/plans/m8/casts_suites.tsv \| wc -l`; the same with `$4` | `19`; `0` |
| 17 | No coverage row reads a zoned timestamp, so the coverage table does not know `ltz` | `python3 -c "import json;d=json.load(open('sql/varka/coverage.json'));print([v for v in d['columns'].values() if v=='timestamp'])"` | `[]` |
| 18 | The size check counts a copied file as added lines: it finds renames, not copies | `grep -n '"--numstat", "-z", "-M"' dev/varka_msg_check.py` | one line |
| 19 | Milestone 8 is not closed today | `git tag -l 'varka-m*'` | `varka-m6`, `varka-m7` |
| 20 | Milestone 8's close leaves the generated files as its Build has them | `m8/VARKA-354.md` 3, step 5: "Done when that Build is green with no generated file changed by the branch"; step 9: "An annotated `varka-m8` on master" | read |
| 21 | A cast to `TIMESTAMP` declines today and raises nothing | `dev/varka_emit.sh "CAST(i AS TIMESTAMP)" --columns i:int`, run on the older worktree at `93625e9c721` | `unsupported expression: CAST(i AS TIMESTAMP)` |
| 22 | A column of a declined type declines by name in the compiler | `grep -n "VarkaTypes.declineReason(br.dataType())" sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaNodeCompiler.java` | one line |
| 23 | Today's check passes, and today's summary | `python3 dev/varka_census.py --check; echo $?`; `--summary` | `0`; `in scope: 182 entries`, `(entry, type) pairs: 896, fused today 588`, `cast pairs over the 13 types: 169 ordered, 106 legal` |

In the table a `\|` inside a command stands for `|`, escaped for the table; fact 14's is grep's
own alternation and is typed as shown.

**What I could not check.**

- No JVM test ran. Whether the probe of `VarkaCastMatrixSuite` compiles a cast over a column
  `x` of type `TIMESTAMP` to a plain decline is read from fact 22 and from fact 21's half (a
  cast *to* `TIMESTAMP`); `dev/varka_emit.sh` refuses a `timestamp` column in `--columns`.
  Part b's command 4 is the check.
- Whether each of the 14 conformance suites cancels its new cast test and fails none of its
  others (fact 11 is read, not run). Part b's command 6 is the check.
- The state of milestone 8's files at its close, which has not happened. Every count of this
  plan is of `a0e3a62cf2b`; the sheets take the close's by command.
- Row 653's layout ("one file an entry"). If its code merges before part a, the curated file
  is no longer one file and part a's B0 stops.

## 4. The design

### 4.1 The file layout

    sql/varka/plans/m8/census.tsv            closed: as the tag varka-m8 has it, never rewritten
    sql/varka/plans/m8/census_curated.txt    closed
    sql/varka/plans/m8/casts.tsv             closed
    sql/varka/plans/m8/casts_fused.tsv       closed
    sql/varka/plans/m8/casts_suites.tsv      closed
    sql/varka/plans/m9/census.tsv            written by dev/varka_census.py
    sql/varka/plans/m9/census_curated.txt    edited by hand, by every row that fuses an entry
    sql/varka/plans/m9/casts.tsv             written by dev/varka_census.py
    sql/varka/plans/m9/casts_fused.tsv       written by VarkaCastMatrixSuite
    sql/varka/plans/m9/casts_suites.tsv      written by Spark's three cast suites under Varka

- **What moves.** Nothing moves by `git mv`. Milestone 9's five files start as copies of
  milestone 8's, taken from the commit part a branches from. Copies, because milestone 8's
  must stay where every plan of milestone 8 cites them.
- **What stays byte-identical.** Milestone 8's five files, to the tag `varka-m8`. And in part
  a, four of milestone 9's five files are byte-identical to the copies they came from; the
  curated file differs by two comment lines (4.3).
- **What is "the close's state".** The tree of the tag `varka-m8`. `m8/VARKA-354.md` puts the
  tag on master after the last code and the post, and lets the close's documents change no
  generated file. If a pull request after the tag moved milestone 8's files before part a
  (a carried row's part, say), part a copies the moved files into `m9/` and restores `m8/`
  from the tag: the moves were milestone 9's work.

### 4.2 The script by milestone (part a)

In `dev/varka_census.py`, the constants `OUT` and `CURATED` leave their place under `COVERAGE`,
and `EXISTING` and `NEW` leave theirs; in their place, after `ORDER`:

    class Milestone(collections.namedtuple("Milestone", "number types new closed")):
        """One milestone's census. `types` are its census types in the census's order and `new`
        the ones the milestone adds, the rest being the types a lane read before it: a judgement
        about the milestone, not a fact of a type, so it stays here. `closed` is None while the
        milestone is open, and once it is closed the sha256 of each of its files as its close
        left them: the script then never writes them, --check holds them to it, and --milestone
        prints their counts.
        """

        def path(self, name):
            """A file of the milestone's, by its name in `FILES`."""
            return f"sql/varka/plans/m{self.number}/{name}"


    # The files a milestone's census is: the census, its curated half, the cast matrix, the
    # compiler's cast probe and the record of Spark's cast suites.
    FILES = ("census.tsv", "census_curated.txt", "casts.tsv", "casts_fused.tsv", "casts_suites.tsv")
    # Milestone 8's types, owner's decision of 14 October 2026. "dec" is DECIMAL up to precision
    # 38: an unscaled long up to precision 18 and a 128-bit lane pair above it.
    M8_TYPES = ["bool", "byte", "short", "int", "bigint", "float", "double", "dec", "date", "ntz"]
    M8_TYPES += ["time", "ym", "dt"]
    M8_NEW = ["bool", "byte", "short", "float", "double", "dec", "ntz"]
    # Milestone 8's files as its close left them (the tag varka-m8), by sha256.
    M8_CLOSED = {
        "census.tsv": "<64 hex digits, from step 3 of 5.1>",
        "census_curated.txt": "<64 hex digits>",
        "casts.tsv": "<64 hex digits>",
        "casts_fused.tsv": "<64 hex digits>",
        "casts_suites.tsv": "<64 hex digits>",
    }
    MILESTONES = {
        8: Milestone(8, M8_TYPES, M8_NEW, M8_CLOSED),
        # Milestone 9 opens on milestone 8's types and split; the row that brings a type into
        # the census adds it to both lists (m9/PLAN.md 2.4).
        9: Milestone(9, M8_TYPES, M8_NEW, None),
    }
    # The milestone whose files this script writes and every reader reads (reader_errors()). To
    # close it: record its files' sha256 in `closed`, add the next milestone and move CURRENT
    # and the readers to it.
    CURRENT = 9
    MILESTONE = MILESTONES[CURRENT]
    OUT = MILESTONE.path("census.tsv")
    CURATED = MILESTONE.path("census_curated.txt")
    NEW = MILESTONE.new

`CASTS_OUT`, `CASTS_FUSED` and `CASTS_SUITES` stay where they are and become
`MILESTONE.path("casts.tsv")`, `MILESTONE.path("casts_fused.tsv")` and
`MILESTONE.path("casts_suites.tsv")`. `import hashlib` joins the imports. Nothing else of the
rendering changes, so the rest of the script reads the same five names as before.

Part a keeps milestone 9 on milestone 8's types and split on purpose: `census.tsv`'s column
`new_lanes` is computed from `NEW`, so any other split would rewrite every line and part a
could not be proven by a byte comparison. Part b changes the entry (4.5).

Five functions change or come in:

1. `summary`. Each old line is in the file once, inside `def summary`:

| old line | new |
| :--- | :--- |
| `def summary(out):` | `def summary(out, milestone=MILESTONE, bands=True):`, and under it the docstring `"""The counts of a census's rows. `bands` adds the decimal lines, which read today's` / `coverage table and so are left out of a closed milestone's summary."""` (two lines) |
| `    for t in ORDER:` (the one followed by the `tag = ` line) | `    for t in milestone.types:` |
| `        tag = "new" if t in NEW else "existing"` | `        tag = "new" if t in milestone.new else "existing"` |
| `    lines.extend(decimal_representation_lines(scoped))` | `    if bands:` and under it, indented four more, the same line |

2. `cast_summary`:

| old line | new |
| :--- | :--- |
| `def cast_summary(rows):` | `def cast_summary(rows, types=len(ORDER)):` |
| `    lines.append(f"cast pairs over the 13 types: {len(ORDER) ** 2} ordered, {len(rows)} legal")` | `    lines.append(f"cast pairs over the {types} types: {types**2} ordered, {len(rows)} legal")` |

   Its call in `main` stays as it is.
3. `conformance_errors`:

| old line | new |
| :--- | :--- |
| `    if sorted(EXISTING + NEW) != sorted(ORDER):` | `    if MILESTONE.types != ORDER or not set(MILESTONE.new) <= set(ORDER):` |
| `        errors.append(f"EXISTING and NEW are not the registry's census types {ORDER}")` | `        errors.append(f"milestone {CURRENT}'s types are not the registry's census types {ORDER}")` |

4. `closed_errors`, `reader_errors`, `closed_summary` and `closed_main`, new, placed in this
   order immediately before `def stale(`. The block below is the text of the file, docstrings
   and doctests included: each doctest is inside its function's docstring, after the summary
   sentence, exactly as shown. Type the backslashes as they are printed (`\\n` inside a doctest
   is two backslashes and an `n`), and keep an expected-output line that follows a lone
   backslash at the offset this plan prints: doctest compares its leading spaces.

        def closed_errors(root=ROOT, milestones=None):
            """Each file of a closed milestone that is not as its close left it, by its sha256.

            >>> import tempfile
            >>> m = Milestone(7, ["int"], [], {"census.tsv": hashlib.sha256(b"a\\n").hexdigest()})
            >>> with tempfile.TemporaryDirectory() as d:
            ...     os.makedirs(os.path.join(d, "sql/varka/plans/m7"))
            ...     missing = closed_errors(d, [m])
            ...     for text in ("a\\n", "b\\n"):
            ...         with open(os.path.join(d, m.path("census.tsv")), "w") as f:
            ...             _ = f.write(text)
            ...         print(closed_errors(d, [m]) == missing, len(closed_errors(d, [m])))
            False 0
            True 1
            >>> missing[0].startswith("sql/varka/plans/m7/census.tsv is not as milestone 7's close left")
            True
            """
            errors = []
            for m in milestones or MILESTONES.values():
                for name, want in (m.closed or {}).items():
                    path = os.path.join(root, m.path(name))
                    got = None
                    if os.path.exists(path):
                        with open(path, "rb") as f:
                            got = hashlib.sha256(f.read()).hexdigest()
                    if got != want:
                        errors.append(
                            f"{m.path(name)} is not as milestone {m.number}'s close left it; restore it"
                            f" with: git checkout varka-m{m.number} -- {m.path(name)}"
                        )
            return errors


        # The files that name the census and cast files by a fixed path: each must name the current
        # milestone's and no other's, so that a milestone's close moves them all or fails here. The Java
        # suites read the folder from VarkaCensusFiles.
        CENSUS_FILES_JAVA = (
            "sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka"
            "/VarkaCensusFiles.java"
        )
        READERS = (
            ".github/workflows/varka-fuzz.yml",
            "README.md",
            "dev/varka_generated_merge.py",
            "dev/varka_new_lane.py",
            "dev/varka_nightly.sh",
            "dev/varka_precommit.sh",
            "dev/varka_regen_generated.sh",
            "dev/varka_regen_inputs.py",
            "docs/sql-varka.md",
            "sql/varka/.gitattributes",
            CENSUS_FILES_JAVA,
        )
        READER_NAMES = re.compile(
            r'plans(?:/|",\s*")m(\d+)(?:/|",\s*")(?:census|casts)|MILESTONE = "m(\d+)"'
        )


        def reader_errors(root=ROOT, current=CURRENT, readers=READERS):
            """Each reader that names a milestone's census or cast files other than the current one's,
            or names none. A line that names a closed milestone's file and says "milestone <n>" itself
            is a record of that milestone and stays.

            >>> import tempfile
            >>> with tempfile.TemporaryDirectory() as d:
            ...     for name, text in (("a.sh", "x sql/varka/plans/m9/casts_suites.tsv"),
            ...                        ("b.py", 'join(root, "plans", "m8", "casts_fused.tsv")'),
            ...                        ("C.java", 'String MILESTONE = "m8";'), ("d.md", "no file"),
            ...                        ("f.md", "milestone 8 closed on plans/m8/census.tsv\\nnow m9")):
            ...         with open(os.path.join(d, name), "w") as f:
            ...             _ = f.write(text)
            ...     for e in reader_errors(d, 9, ("a.sh", "b.py", "C.java", "d.md", "e.sh", "f.md")):
            ...         print(e)
            b.py names milestone 8's census files, and the census is milestone 9's
            C.java names milestone 8's census files, and the census is milestone 9's
            d.md is listed as a reader of the census files and names none
            e.sh is listed as a reader of the census files and is missing
            """
            errors = []
            for path in readers:
                full = os.path.join(root, path)
                if not os.path.exists(full):
                    errors.append(f"{path} is listed as a reader of the census files and is missing")
                    continue
                named, wrong = set(), set()
                with open(full, encoding="utf-8") as f:
                    for line in f:
                        for a, b in READER_NAMES.findall(line):
                            n = int(a or b)
                            named.add(n)
                            closed = n in MILESTONES and MILESTONES[n].closed is not None
                            if n != current and not (closed and f"milestone {n}" in line):
                                wrong.add(n)
                if not named:
                    errors.append(f"{path} is listed as a reader of the census files and names none")
                for n in sorted(wrong):
                    errors.append(
                        f"{path} names milestone {n}'s census files, and the census is milestone"
                        f" {current}'s"
                    )
            return errors


        def closed_summary(m, root=ROOT):
            """A closed milestone's summary, from its two committed tables alone.

            >>> import tempfile
            >>> m = Milestone(7, ["int", "date"], ["date"], {})
            >>> census = [["year", "Year", "datetimeExpressions", "in", "", "date", "int", "date", "date",
            ...            "done", "", "", ""],
            ...           ["hex", "Hex", "mathExpressions", "out", "STRING"] + [""] * 8]
            >>> casts = [["int", "int", "yes", "yes", "yes", "identity", "", "exhaustion", "", "332", ""]]
            >>> with tempfile.TemporaryDirectory() as d:
            ...     os.makedirs(os.path.join(d, "sql/varka/plans/m7"))
            ...     tables = (("census.tsv", HEADER, census), ("casts.tsv", CAST_HEADER, casts))
            ...     for name, head, rows in tables:
            ...         with open(os.path.join(d, m.path(name)), "w") as f:
            ...             _ = f.write(render_rows(head, rows))
            ...     text = closed_summary(m, d).splitlines()
            >>> [line for line in text if line.startswith(("in scope:", "  int ", "  date ", "cast p"))]
            ['in scope: 1 entries (1 classes)', '  int     existing    0 / 0', \
        '  date    new         1 / 1', 'cast pairs over the 2 types: 4 ordered, 1 legal']
            >>> any(line.startswith("decimal entries") for line in text)
            False
            """
            rows = {}
            for name in ("census.tsv", "casts.tsv"):
                with open(os.path.join(root, m.path(name)), encoding="utf-8") as f:
                    rows[name] = [line.split("\t") for line in f.read().splitlines()[1:]]
            return (
                summary(rows["census.tsv"], m, bands=False)
                + "\n"
                + cast_summary(rows["casts.tsv"], len(m.types))
            )


        def closed_main(args):
            """--milestone for a milestone that is not the current one: its counts from its committed
            files, or its files held to its close. Nothing is rendered: the registry, the coverage table
            and the compiler have moved on since its close."""
            m = MILESTONES.get(args.milestone)
            if m is None or m.closed is None:
                print(f"census: no closed milestone {args.milestone}", file=sys.stderr)
                return 2
            if args.check:
                errors = closed_errors(milestones=[m])
                for e in errors:
                    print("census: " + e, file=sys.stderr)
                return 1 if errors else 0
            if not args.summary:
                print(
                    f"census: milestone {m.number} is closed and its files are not rewritten; --summary"
                    " prints its counts and --check holds its files to its close",
                    file=sys.stderr,
                )
                return 2
            sys.stdout.write(closed_summary(m))
            return 0

5. `main`, three insertions:

        # after the line that adds --self-test, before `args = ap.parse_args()`
        ap.add_argument(
            "--milestone",
            type=int,
            default=CURRENT,
            help="a closed milestone, for its --summary or --check (default: the current one)",
        )

        # immediately before the line `    out, errors = build()`
        if args.milestone != CURRENT:
            return closed_main(args)

        # immediately after the line `    errors += conformance_errors()`
        errors += closed_errors()
        errors += reader_errors()

   *27 November 2026, from `VARKA-653.md` 5.5:* if `main` already holds the line
   `ap.add_argument("--docs", ...)` after the `--self-test` line, the `--milestone` argument goes
   after the `--docs` line; and in the docstring the `--milestone 8 --summary` line goes after the
   `--summary` line whatever stands above it.

A line of a reader that names a closed milestone's file and holds the lower-case words `milestone 8`
itself is a record of that milestone, and `reader_errors` lets it stay (5.1, rule R1).

The module docstring, four edits, each old text on one line of the file:

| old | new |
| :--- | :--- |
| `"""The census of Spark's scalar expressions over milestone 8's type set (m8/PLAN.md 2).` | `"""The census of Spark's scalar expressions over the current milestone's type set (m8/PLAN.md 2).` |
| `  dev/varka_census.py --summary  # print the counts only` | the same line, and after it the new line `  dev/varka_census.py --milestone 8 --summary  # a closed milestone's counts` |
| `The judgement half is m8/census_curated.txt:` | `The judgement half is census_curated.txt:` |
| `writes m8/casts_fused.tsv, from which` | `writes casts_fused.tsv, from which` |

The comment above `EXISTING` ("The milestone's split of them, owner's decision of 14 October
2026: ...", five lines, down to the line `NEW = [...]`) is deleted with the two lists: the
class's docstring and the comment above `M8_TYPES` say what it said.

### 4.3 The readers (part a)

| reader | today | after part a |
| :--- | :--- | :--- |
| `VarkaCastMatrixSuite` (3 sites: `committed`, `fusion`, `plantedFusion`) | `Path.of(home(), "sql", "varka", "plans", "m8", ...)` | `VarkaCensusFiles.of(home(), "casts.tsv")` and `VarkaCensusFiles.of(home(), FUSED_FILE)`. Its class comment's "Milestone 8's cast matrix ({@code sql/varka/plans/m8/casts.tsv}" becomes "The current milestone's cast matrix ({@code casts.tsv} of {@link VarkaCensusFiles}" |
| `VarkaTypeConformance.Registers.tsv` | `Path.of(home, "sql", "varka", "plans", "m8", name)` | `VarkaCensusFiles.of(home, name)`, and the line `import java.nio.file.Path;` is deleted from the file's imports: that call was its only use, and checkstyle fails an unused import (the other two readers still use `Path` and keep theirs); its comment's `{@code m8/<name>}` becomes "the current milestone's {@code <name>}" |
| `VarkaSuiteRecord.plans` (sql/core tests) | `Path.of(home(), "sql", "varka", "plans", "m8", file)` | `VarkaCensusFiles.of(home(), file)`, with the import; the class already imports `VarkaMatrixRuns` from the same catalyst test package |
| `dev/varka_new_lane.py` | `CENSUS = "sql/varka/plans/m8/census.tsv"` | `m9` |
| `dev/varka_regen_generated.sh` | two `want sql/varka/plans/m8/...` lines | `m9` |
| `dev/varka_nightly.sh` | `git diff --exit-code -- sql/varka/plans/m8/casts_suites.tsv` | `m9` |
| `.github/workflows/varka-fuzz.yml` | the same `git diff` line | `m9` |
| `dev/varka_precommit.sh` | `lane_inputs` holds `sql/varka/plans/m8/census\.tsv`; `census_inputs` holds `/Varka[A-Za-z]*Conformance(Suite)?\.java` | `m9`; and the new class joins the pattern, as step 6 of 5.1 writes it, so that an edit of it runs the census check |
| `dev/varka_regen_inputs.py` | three strings of `selftest` | `m9` |
| `dev/varka_generated_merge.py` | `review_cases`: `os.path.join(root, "sql", "varka", "plans", "m8", "casts_fused.tsv")` | `"m9"` |
| `sql/varka/.gitattributes` | four lines `/plans/m8/<file> merge=varka-generated` | the same four with `m9`. Milestone 8's lines go: a closed file is never merged, and the driver's list is what `dev/varka_regen_generated.sh --all` regenerates |
| `docs/sql-varka.md` | two sentences of prose name `sql/varka/plans/m8/census.tsv` and `.../casts_suites.tsv`; the support table's first line names three | `m9` by hand in the prose; the block by `python3 dev/varka_census.py` |
| `README.md` | one sentence names `sql/varka/plans/m8/census.tsv` | `m9` |

The new class, whole (catalyst test sources, package
`org.apache.spark.sql.catalyst.expressions.codegen.varka`, with the license header of a
neighbouring Java file):

    import java.nio.file.Path;

    /**
     * Where the census and the cast matrix are: the files of the current milestone, which
     * {@code dev/varka_census.py} writes. Its {@code --check} holds {@link #MILESTONE} to its
     * own {@code CURRENT}.
     */
    public final class VarkaCensusFiles {

      /** The folder under {@code sql/varka/plans} that holds the current milestone's files. */
      public static final String MILESTONE = "m9";

      private VarkaCensusFiles() {
      }

      /** The file {@code name} of the current milestone, under the Spark home {@code home}. */
      public static Path of(String home, String name) {
        return Path.of(home, "sql", "varka", "plans", MILESTONE, name);
      }
    }

Milestone 9's curated file differs from the copy in two comment lines of its header: "derived
from m8/casts_fused.tsv, VARKA-606)" becomes "derived from casts_fused.tsv beside this file,
VARKA-606)", and "Rows are milestone 8's task numbers (m8/PLAN.md 3)." becomes "Rows are task
numbers of m8/PLAN.md 3 and of m9/PLAN.md 3.". Task numbers do not repeat across the two
tables, so a number needs no milestone beside it.

Three comments that say "milestone 8's census" stay true only as history and are reworded in
the part that touches their file: `VarkaCoverageSuite.censusType` and `castsOf` (part a, to
"the census" and "`casts.tsv`"), and `VarkaTimeCompiler` (part b, since part a touches no main
source).

### 4.4 Milestone 8, frozen, and how its totals stay reproducible

- **Frozen.** `MILESTONES[8].closed` holds the sha256 of each of the five files as the tag
  `varka-m8` has them. `--check` fails on a file that differs, with the command that restores
  it. The check needs no tag in the clone, so it runs in CI as it is.
- **Never rewritten.** The script writes `OUT`, `CASTS_OUT` and the docs block, all of the
  current milestone. `--milestone 8` with neither `--summary` nor `--check` exits 2.
- **Reproducible, two ways.** (1) `python3 dev/varka_census.py --milestone 8 --summary` prints
  milestone 8's counts from its two committed tables alone: every line of the close's summary
  but the three decimal-band lines, which were never a count of the census but of the coverage
  table. (2) `git show varka-m8:sql/varka/plans/m8/census.tsv` is the file, and the script of
  the tag renders it again on the tag's tree.
- **The reasons milestone 8's census names stay as they are.** By the note of 25 November 2026
  in `m8/PLAN.md` 1.1, a pair whose reason names a row moved to milestone 9 (30, 95, 103, 351,
  364, 366) "carries its reason as item 2 asks of a named `m10/SCOPE.md` row". And row 663's
  three names stay as the sync curated them: `try_abs` and `try_negative` in scope and unfused
  with row 663 in their `rows`, `farm_fingerprint` out as a `BINARY` input. Row 672 fuses the
  first two in milestone 9's file.
- **The seven date functions read two ways across the two milestones, on purpose.** Milestone
  8's files are frozen, so `--milestone 8 --summary` counts no timestamp for them; milestone
  9's summary counts `ntz` and `ltz` (4.7, rule 4). For this reason alone `ntz` admits 7 pairs
  more in milestone 9's summary than in milestone 8's.

### 4.5 `ltz` enters the type set (part b)

The census's types are the registry's, so the type enters there:

- `VarkaTypes`, the `TIMESTAMP` case: `new Info(id, null, "TIMESTAMP", ...)` becomes
  `new Info(id, "ltz", "TIMESTAMP", ...)`. Nothing else of the entry changes: its admission
  stays `Declined`, its lanes empty, its conformance `NotLanded`. Row 656 turns those.
- `sql/varka/types.tsv` is regenerated; its `TIMESTAMP` line reads `ltz` in the third column.
- `MILESTONES[9]` becomes `Milestone(9, M8_TYPES + ["ltz"], ["ltz"], None)`, and part a's
  two-line comment above it ("Milestone 9 opens on milestone 8's types and split; ...") is
  replaced by the comment
  "Milestone 9 adds TIMESTAMP_LTZ, "ltz", to milestone 8's thirteen (m9/PLAN.md 2.4); the
  nanosecond types join both lists with row 661." So the seven types milestone 8 added read
  `existing` in milestone 9's summary, and `new_lanes` in `m9/census.tsv` holds `ltz` alone.
- `SIMPLE_COLUMN_TYPES` gains the line `"timestamp": "ltz",` after `"timestamp_ntz": "ntz",`, so
  that row 656's coverage columns have a census type (`VARKA-656.md` part B greps it).
- The two literals of fact 14: `[":---"] * 14` becomes `[":---"] * (len(ORDER) + 1)`
  (`cast_summary` was done in part a).
- The comment above `CASTS_FUSED`: on its second line, "of the thirteen types" becomes "of the
  census's types".
- The curated file's header line "Types are bool byte short int bigint float double dec date
  ntz time ym dt, or a group:" gains ` ltz` after `dt`.

`ltz` is the last type of the order, because `TIMESTAMP` is the last id of `VarkaTypeId`.

### 4.6 The 20 names with the zoned reason (part b)

The line `LTZ = "TIMESTAMP with local time zone: owner's decision, 14 October 2026"` is deleted
from the script, and `NANOS` takes its place. In `NAME_EXCLUSIONS` each of the 20 lines that
ends in `LTZ,` or begins `"make_timestamp": LTZ +` or `"try_make_timestamp": LTZ +` changes:

| names | count | after |
| :--- | ---: | :--- |
| `current_timestamp`, `now` | 2 | `FOLDED`: "a constant per query: folded to a literal before Varka sees it" (`m9/PLAN.md` 2.4) |
| `timestamp_nanos`, `unix_nanos` | 2 | `NANOS = "nanosecond timestamps, precision 7 to 9: phase 2 of milestone 9, rows 661 and 675"` |
| `timestamp_seconds`, `timestamp_millis`, `timestamp_micros`, `unix_seconds`, `unix_millis`, `unix_micros` | 6 | deleted: curated, row 667 |
| `from_utc_timestamp`, `to_utc_timestamp`, `convert_timezone`, `date_trunc`, `months_between`, `make_timestamp`, `try_make_timestamp`, `make_timestamp_ltz`, `try_make_timestamp_ltz` | 9 | deleted: curated, row 670 |
| `timestamp` | 1 | deleted: curated as a cast alias, row 669; `CAST_ALIASES` gains the line `"timestamp": "ltz",` after `"time": "time",` |

That is the row's "each of the 18 entries that come into scope has a row or a reason": 16 get a
row, 2 a reason, and 2 were never Varka's.

The 16 curated lines. A line's types are the types its class declares in `inputTypes`, which
is the census's rule (`m8/PLAN.md` 2.1); a parameter that is a literal in every fused form (a
zone, a format, `roundOff`) is not listed, as `trunc` and `next_day` list none today.

| name | types | result | need | rows | from the code |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `timestamp_seconds` | `NUM` | `ltz` | semantics | 667 | `SecondsToTimestamp.inputTypes` is `NumericType` |
| `timestamp_millis`, `timestamp_micros` | `INTEGRAL` | `ltz` | semantics, plumbing | 667 | `IntegralToTimestampBase.inputTypes` is `IntegralType` |
| `unix_seconds`, `unix_millis`, `unix_micros` | `ntz,ltz` | `bigint` | plumbing | 667 | `TimestampToLongBase.inputTypes` holds `AnyTimestampType`: both microsecond timestamps |
| `from_utc_timestamp`, `to_utc_timestamp` | `ltz` | `ltz` | semantics | 670 | `UTCTimestamp.inputTypes` holds `TimestampType` |
| `convert_timezone` | `ntz` | `ntz` | semantics | 670 | `ConvertTimezone.inputTypes` holds `TimestampNTZType`: it takes and returns a `TIMESTAMP_NTZ` |
| `date_trunc` | `ltz` | `ltz` | semantics | 670 | `TruncTimestamp.inputTypes` holds `TimestampType` |
| `months_between` | `ltz` | `double` | semantics | 670 | `MonthsBetween.inputTypes` is two `TimestampType` and a `BooleanType` |
| `make_timestamp`, `try_make_timestamp`, `make_timestamp_ltz`, `try_make_timestamp_ltz` | `date,time,int,dec` | `ltz` | semantics | 670 | the builders give `MakeTimestampFromDateTime` (a `DATE` and a time) or `MakeTimestamp` (five `INT` and a `DECIMAL(16, 6)`), as `make_timestamp_ntz` is curated |
| `timestamp` | `legal` | `ltz` | semantics | 669 | a cast alias: sources from `legal()`, fused types `matrix` |

Each line's fused field is empty (`matrix` for the alias). The exact lines, with their notes,
are in the script of 5.2.

### 4.7 Which entries of milestone 8's census admit `ltz`, and who owes each (part b)

Three rules, applied by the script of 5.2 and by nothing else:

1. **An entry whose types are `ALL`** admits `ltz` with no edit of its types (fact 7).
2. **An entry that lists `ntz`** gets `ltz` after `ntz` in its types, and in its result where
   the result lists `ntz`. Spark's fields and timestamp arithmetic take both timestamps
   (`AnyTimestampType`, or a `DATE` parameter that either timestamp casts to), and
   `m9/PLAN.md` rows 668 and 671 name these entries for `TIMESTAMP_LTZ`.
3. **A cast alias** (types `legal`) takes `ltz` as a source from `legal()`.
4. **A date function that Spark takes over a timestamp through a cast to `DATE`** (`add_months`,
   `datediff`, `date_diff`, `date_add`, `dateadd`, `date_sub`) gets `ntz,ltz` at the end of its
   types (the owner, 27 November 2026; `VARKA-660.md` 16). Milestone 8's census lists no
   timestamp for these six, and stays so (4.4). Row 672 owes `ntz`, and row 668 owes `ltz`.
   `unix_date` is not among them (the lead, 27 November 2026; `VARKA-661.md` 4.3): it takes a
   `DATE`, and Spark casts no timestamp to it. A run of Spark's analyzer on master
   `1924c7a5afb` rejected `unix_date(c)` over `TIMESTAMP_NTZ` and over `TIMESTAMP`, under both
   ANSI settings, with `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE`. Its line keeps `date` alone,
   as in milestone 8's census.

For each such entry the script appends the row that owes `ltz` to the entry's `rows`, appends
"ltz is row N's" to its note, and, where the need is `done`, sets it to `plumbing` for a row
whose kernels read no zone (656, 667) and to `semantics` for the others: an entry is `done`
only when every type it admits is fused (fact 8). The fused field is not touched.

| row that owes `ltz` | entries | from `m9/PLAN.md` section 3 |
| ---: | :--- | :--- |
| 656 | `coalesce if ifnull nullif nvl nvl2 when between isnull isnotnull equal_null in <=> = == > >= < <= greatest least` (21) | row 656: "the load, the comparisons, the null tests, `IN`, `greatest`, `least`, the choices and the output" |
| 667 | `hash xxhash64 in_set known_not_null try_eval` (5) | row 667 names each |
| 669 | `cast try_cast boolean tinyint smallint int bigint float double decimal date time` (12) | row 669: "each of the 23 pairs" |
| 657 | `hour minute second` (3) | row 657: "`hour`, `minute`, `second` and `CAST(ts AS DATE)`" |
| 668 | `day dayofyear dayofmonth last_day month next_day quarter trunc dayofweek weekday weekofyear year extract date_part datepart time_bucket` (16) | row 668 names each |
| 671 | `ntz_minus_ntz datetime_sub ntz_plus_dt ntz_plus_ym timestampadd` (5) | row 671: "`ts - ts`, `ts` plus and minus a day-time and a year-month interval, `timestampadd` and `datetime_sub`" |
| 668, and 672 for `ntz` | `add_months datediff date_diff date_add dateadd date_sub` (6) | rule 4. `ltz`: row 668, by the rule of this section for an entry that "lists `ntz` otherwise", a field of a timestamp, and beside `last_day`, `next_day` and `trunc`, which take a `DATE` the same way. `ntz`: row 672, "the existing temporal types' tail", named because its done-when is item 3 of `m9/PLAN.md` 1.2, which these pairs join |

That is 69 entries on master `a0e3a62cf2b`: 28 by rule 1 (24 registered and 4 implicit), 24 by
rule 2, 10 by rule 3 and 7 by rule 4. The implicit entries keep their names (`ntz_minus_ntz`,
`ntz_plus_dt`,
`ntz_plus_ym`): a name is a key that `VarkaTypeConformance` reads, and row 688's premise finds
`ntz_plus_dt` by its class, `TimestampAddInterval`.

**An entry on no list.** Milestone 8's census moves until its close, so the close's file may
hold an entry that admits `ltz` by one of the rules and that no list above names. The script
does not stop: it gives the entry the row of its kind, by this table, tested from the top, and
prints `default: <name> -> row <N> (<kind>)`.

| the entry | row | kind, as the script prints it |
| :--- | ---: | :--- |
| its types are `legal`, or it is implicit with the family `conversion` | 669 | a cast |
| it lists `ntz`, and it is implicit or its result lists `ntz` | 671 | timestamp arithmetic |
| it lists `ntz` otherwise | 668 | a field of a timestamp |
| its types are `ALL` and its result is `bool` or `same` | 656 | a comparison, a null test or a choice |
| its types are `ALL` otherwise | 667 | a function that reads no zone |

**A listed entry that changed.** A name on a list whose types no longer hold `ALL`, `ntz` or
`legal` is left as it is, and the script prints `skipped: <name> ...`. A name on a list that is
no longer in the file prints `absent: <name> ...`. Neither stops the script.

The builder copies every `default:`, `skipped:` and `absent:` line into the pull request's
description, under the heading "Entries the plan did not name". There is nothing to ask: the
owner reads the list in review, and a row that disagrees moves its cell in its own pull
request. One case is a stop, in 5.2: command 8 fails because `hour`, `minute`, `second` or the
entry of class `TimestampAddInterval` or `SubtractTimestamps` is skipped or absent. Rows 656,
657 and 688 read those five lines, so the part ends there and the builder reports the
script's line for the entry.

### 4.8 The cast matrix (part b)

**Legality.** From `Cast.canCast` and `Cast.canAnsiCast` (`canTryCast` is `canAnsiCast` for
these types):

| pair | LEGACY | ANSI and TRY | count |
| :--- | :--- | :--- | ---: |
| `ltz` to `ltz` | yes | yes | 1 |
| each of the seven numeric types to `ltz` (`(_: NumericType, TimestampType)`) | yes | yes | 7 |
| `ltz` to each of them (`(TimestampType, _: NumericType)`) | yes | yes | 7 |
| `bool` to `ltz`, `ltz` to `bool` (in `canCast` alone) | yes | no | 2 |
| `date`, `ntz`, `time` to `ltz` | yes | yes | 3 |
| `ltz` to `date`, `ntz`, `time` | yes | yes | 3 |
| `ltz` with `ym` or `dt`, either way | no | no | 0 |

23 pairs, 21 of them under ANSI and TRY. In `legal`, after `num = NUMERIC`:

    if "ltz" in (src, dst):
        other = dst if src == "ltz" else src
        zoned = num + ["date", "ntz", "time"]
        # TIMESTAMP with BOOLEAN, either way, is canCast's alone.
        return other in zoned + ["bool"], other in zoned

The docstring of `legal` is replaced whole. Its old text is four lines, from `"""(LEGACY, ANSI)
legality, transcribed from Cast.canCast (Cast.scala 249 to 340) and` to `function's answers to
Cast's over every pair."""`. The new docstring:

    """(LEGACY, ANSI) legality, transcribed from Cast.canCast and Cast.canAnsiCast for the type
    set; TRY follows Cast.canTryCast, which for atomic types is canAnsiCast. The census's own
    check is row 328: a suite holds this function's answers to Cast's over every pair.


    >>> [legal("ltz", t) for t in ("bool", "int", "date", "ym", "ltz")]
    [(True, False), (True, True), (True, True), (False, False), (True, True)]
    >>> types = M8_TYPES + ["ltz"]
    >>> sum(any(legal(a, b)) for a in types for b in types if "ltz" in (a, b))
    23
    >>> sum(legal(a, b)[1] for a in types for b in types if "ltz" in (a, b))
    21
    """

**Kind and semantics.** In `cast_kind`, the first block ends with the line `return "identity",
""`. Immediately after that line, and before `if dst == "bool":`, two lines come in:

    if "ltz" in (src, dst):
        return ltz_cast_kind(src, dst)

The new function goes immediately before `def cast_proof(`. It is given whole. The kind strings
(the first of each pair) are exact: the summary counts them, and rows 657 and 669 read them. The
semantics strings are prose that no test pins; type them as printed, so that the `casts.tsv` the
builder commits is the one this plan's prototype wrote. The text is from `Cast.castToTimestamp`
and from the `TimestampType` case of each other `castTo` method.

    def ltz_cast_kind(src, dst):
        """cast_kind for a pair with TIMESTAMP_LTZ on one side, from Cast.castToTimestamp and the
        TimestampType case of each other castTo method. A pair with DATE, TIMESTAMP_NTZ or TIME
        reads the session time zone; the sixteen with a number or a BOOLEAN read none.

        >>> ltz_cast_kind("date", "ltz")[0], cast_kind("date", "ltz")[0], cast_kind("ltz", "int")[0]
        ('local to instant', 'local to instant', 'floor division')
        """
        if src == "bool":
            return "select", "1 microsecond or 0"
        if src in INTEGRAL:
            return (
                "scale up",
                "seconds times a million by TimeUnit.SECONDS.toMicros, which saturates at a long's"
                " ends: no error in any mode",
            )
        if src in MANTISSA:
            return (
                "truncation",
                "seconds times a million in double, truncated toward zero; LEGACY saturates and"
                " nulls a NaN or an infinity; ANSI and TRY range-check the product and reject a NaN"
                " or an infinity",
            )
        if src == "dec":
            return (
                "scale up",
                "seconds times a million, then BigDecimal.longValue: what is below a microsecond"
                " is dropped and a product past a long keeps its low 64 bits, in every mode",
            )
        if src == "date":
            return (
                "local to instant",
                "midnight of the day in the session zone; a midnight inside a gap takes the gap's"
                " end (daysToMicros)",
            )
        if src == "ntz":
            return (
                "local to instant",
                "the local time in the session zone: the earlier offset in an overlap, a time in a"
                " gap moved later by the gap's length (convertTz)",
            )
        if src == "time":
            return (
                "session date",
                "the date comes from CURRENT_DATE in the session zone, a literal after"
                " ComputeCurrentTime; then local to instant",
            )
        if dst == "bool":
            return "test", "t != 0"
        if dst == "bigint":
            return "floor division", "floorDiv of the microseconds by a million, exact"
        if dst in INTEGRAL:
            return (
                "floor division",
                "floorDiv of the microseconds by a million, then a range check: LEGACY and TRY null,"
                " ANSI raises CAST_OVERFLOW",
            )
        if dst in MANTISSA:
            return (
                "rounding",
                "the microseconds divided by a million in double; FLOAT narrows that quotient",
            )
        if dst == "dec":
            return (
                "rescale",
                "the microseconds as DECIMAL(19, 6), HALF_UP to the target scale; overflow: LEGACY"
                " null, ANSI NUMERIC_VALUE_OUT_OF_RANGE",
            )
        if dst == "date":
            return (
                "instant to local",
                "the local date in the session zone: the instant plus the zone's offset,"
                " floor-divided by a day (microsToDays)",
            )
        if dst == "ntz":
            return "instant to local", "the instant plus the session zone's offset (convertTz)"
        if dst == "time":
            return (
                "instant to local",
                "the local time of day in the session zone, truncated to the precision",
            )
        return "?", ""

One fact of that function differs from the other types: a `TIMESTAMP` cast to `INT`, `SMALLINT` or
`TINYINT` out of range is null under LEGACY (`Cast.errorOrNull`), where the integral types wrap.

**Proof.** A pair with `date`, `ntz` or `time` on the other side reads the session zone, and no
proof over one long decides it. Three edits of `cast_proof`. The first two are shown as the file
reads after them, from the new constant above the function to the function's new first
statement (the docstring's old last words were "useful time (VARKA-241.md)."""):

    # The proof of a pair that reads the session time zone: the sweep over every zone the session can
    # name, at each change of offset (m9/PLAN.md 2.2).
    ZONE_PROOF = "zone sweep (VarkaZoneSweepSuite) over every zone's changes, and the fuzzer"


    def cast_proof(src, dst):
        """How a pair is proven: exhaustion where the source has at most 32 bits; SMT for an integer
        rule over a 64-bit source; sampled regions and the fuzzer for a double source and for a
        64-bit source rounding to a floating type, which floating-point theory does not decide in
        useful time (VARKA-241.md); the sweep of every zone where the pair reads the session time
        zone, which no proof over one long decides."""
        if "ltz" in (src, dst) and {src, dst} & {"date", "ntz", "time"}:
            return ZONE_PROOF

The third: in the test that follows the one of the 32-bit sources, the tuple
`("bigint", "dt", "time", "ntz", "dec")` gains `"ltz"` after `"ntz"`. `VarkaZoneSweepSuite` is
row 684's suite.

So, of the 23: the zone sweep 6; exhaustion 5 (`bool`, `byte`, `short`, `int`, `float` to
`ltz`); SMT 9 (`bigint` and `dec` to `ltz`; `ltz` to `bool`, `byte`, `short`, `int`, `bigint`,
`dec` and itself); sampled regions 3 (`double` to `ltz`; `ltz` to `float` and `double`).

**Rows.** In `cast_rows`, immediately after its first line, `lanes = {src, dst}`, and before the
comment "The decimal and interval casts both ways" and its test `if lanes in ({"dec", "ym"}, ...`:
a pair of `ltz` with `dec` or `ntz` must not reach the branches below, which would answer 103,
350 or 338.

    # TIMESTAMP_LTZ's pairs are row 669's (m9/PLAN.md 3), but the first kernel of each zone
    # conversion: the cast to DATE is row 657's, the casts from DATE and TIMESTAMP_NTZ row 690's.
    if "ltz" in lanes:
        if (src, dst) == ("ltz", "date"):
            return "657,669"
        return "690,669" if dst == "ltz" and src in ("date", "ntz") else "669"

**The probe.** `VarkaCastMatrixSuite` needs no edit for it: `ltz` joins `TYPES` from the
registry with its one form, `TIMESTAMP`. The probe gains one line for each legal cast of two
forms: 2 with `bool`, 8 with the four integral types, 4 with `float` and `double`, 4 with the
two decimals, 2 with `date`, 2 with `ntz` and 6 with the three precisions of `time`: 28 lines,
each `declined` where legal and `illegal` where not, none `fused`. The identity has no line.

**The suites' record.** `VarkaSuiteRecord.census` reads the registry, so the 19 lines whose
target form is `TIMESTAMP` change their `to` from `-` to `ltz`, and the file is sorted by its
key again. No verdict moves.

### 4.9 An identity over a type no kernel reads (part b)

`pair_credit` counts an identity cast as fused with no coverage row (fact 12), because the
analyzer removes it. With `ltz` that rule would credit the alias `timestamp` with one fused
pair while no kernel can load a `TIMESTAMP` column, and fact 13 would then fail the check until
the registry named a conformance owner for a type that has not landed. So the rule gains its
premise:

    # The census types a kernel reads, from the registry's admission: an identity cast over any
    # other is no fused pair, since its column does not load.
    READ = {r["census"] for r in types_rows() if r["admission"] not in ("declined", "owed")}

    def pair_credit(probe, cov, src, dst, modes, read=None):
        ...
        if cast_kind(src, dst)[0] == "identity":
            return "all" if src in (READ if read is None else read) else ""

`READ` and its comment go immediately before `def pair_credit(`. In the docstring, three lines
are replaced. Old, line by line:

    An identity (cast_kind's) is "all" with no fused form and no coverage row: the analyzer removes
    it before a cast exists, so it never reaches Varka (the owner's decision, 11 November 2026). A
    pair that fuses only inside another operator (CONTEXT_CASTS) is "".

New:

    An identity (cast_kind's) over a type a kernel reads (`read`, by default READ) is "all" with no
    fused form and no coverage row: the analyzer removes it before a cast exists, so it never
    reaches Varka (the owner's decision, 11 November 2026); over a type no kernel reads it is "".
    A pair that fuses only inside another operator (CONTEXT_CASTS) is "".

And one more doctest, the last of the docstring, after the lines `>>> pair_credit(probe, cov,
"date", "int", ("try",)) is None` and `True`:

    >>> pair_credit(probe, cov, "int", "int", ("ansi",), read={"date"})  # no kernel reads it
    ''

No cell of the thirteen types moves by this: each of them is read (`on_lane`, `by_precision` or
`as_condition`). Row 656 turns the registry's admission in two steps (`VARKA-656.md` 4.1): to
`Owed("656")` in its part A, which `READ` still leaves out, so no census cell moves there; then
to `OnLane` in its part B, where the alias's identity counts by itself.

### 4.10 What moves and what does not

Predictions, registered 27 November 2026 on master `a0e3a62cf2b`; section 7 gives the rule and
the command that restates each at the build.

| | before | after part a | after part b |
| :--- | ---: | ---: | ---: |
| entries in scope | 182 | 182 | 198 |
| (entry, type) pairs admitted | 896 | 896 | 1012 |
| pairs fused | 588 | 588 | 585 |
| `ltz` pairs fused of admitted | none | none | 0 of 70 |
| entries with need `done` | 85 | 85 | 62 |
| legal cast pairs | 106 | 106 | 129 |
| legal under ANSI and TRY | 98 | 98 | 119 |
| lines of the probe | 365 | 365 | 393 |
| names out with the zoned reason | 20 | 20 | 0 |

- **Part a moves no count.** Four files of `m9/` equal their copies byte for byte, the summary
  is the same text, and the docs change in three lines, all paths.
- **Part b, the 70.** 62 entries by the rules of 4.7 and 8 of the new ones (`unix_seconds`,
  `unix_millis`, `unix_micros`, `from_utc_timestamp`, `to_utc_timestamp`, `date_trunc`,
  `months_between`, `timestamp`). `m9/PLAN.md` 1.2 expected "about 66".
- **Part b, the 46 pairs the other types gain** (1012 is 896 plus 70 plus 46): `bool` 1, `byte`
  4, `short` 4, `int` 8, `bigint` 4, `float` 2, `double` 2, `dec` 6, `date` 5, `ntz` 5, `time`
  5. Each is a new entry's parameter (4.6), or the alias `timestamp`'s source.
- **Part b, the 3 fused pairs that go.** `cast` counts a source as fused only when every legal
  pair from it fuses (`matrix_fused`). `date` to `ltz` and `ntz` to `ltz` are new legal pairs,
  so `cast` loses `ntz` and `try_cast` loses `date` and `ntz`: each becomes fused in part. They
  come back with row 690's two casts.
- **Part b, the 23 entries no longer `done`:** `isnull`, `isnotnull`, the 15 fields from `day`
  to `year`, the alias `date`, and the 5 implicit timestamp operators.
- **Item 3 of `m9/PLAN.md` 1.2.** Over `date`, `ntz`, `time`, `ym` and `dt` the census reads 213
  fused of 282 before and 210 of 297 after part b.
- ***27 November 2026*, a correction: the date functions count for `ntz` and `ltz`** (4.7, rule
  4; `VARKA-660.md` 16). Part b's numbers, here and wherever this file repeats them, read: 77
  `ltz` pairs for 70; 1026 pairs for 1012; 53 pairs gained by the other types for 46, `ntz`
  12 of them for 5; 69 entries edited for 62; 27 no longer `done` for 23, the four more
  being `add_months`, `datediff`, `date_diff` and `unix_date`; 58 entries `done` for 62; and
  210 of 304 for 210 of 297 in item 3. The pairs fused stay 585.
- ***27 November 2026*, a second correction: `unix_date` does not count for `ntz` and `ltz`**
  (4.7, rule 4; `VARKA-661.md` 4.3): Spark rejects it over both. Where this file says the
  seven date functions, read six. Part b's numbers, here and wherever this file repeats them,
  read: 76 `ltz` pairs for 77; 1024 pairs for 1026; 52 pairs gained by the other types for
  53, `ntz` 11 of them for 12; 68 entries edited for 69, 6 of them by rule 4 for 7; 26 no
  longer `done` for 27, the three more than 23 being `add_months`, `datediff` and
  `date_diff`; 59 entries `done` for 58; and 210 of 303 for 210 of 304 in item 3. The script
  of 5.2 prints `68 entries edited, 26 of them no longer done, 16 added; 0 by default, 0
  skipped, 0 absent`, and the summary reads `  ltz     new         0 / 76`.
- **What does not move in either part:** `sql/varka/coverage.json`, `emitted_bytes.json`,
  `width_audit.json`, `sql/varka/fuzz/types.tsv`, every benchmark results file, and milestone
  8's five files.

### 4.11 Edge cases and declines

The row emits no kernel, so null rows, the ends of a long, an empty batch and the vector widths
do not arise. Nothing new declines: a `TIMESTAMP` column still declines with the registry's
reason, "a timestamp column: its arithmetic is in the session time zone, which is not lane
arithmetic" (`VarkaTypesSuite`, the test "a column of an owed, declined or condition-only type
declines naming its type"), until row 656 removes it. The script's own refusals are the error
texts of 4.2.

### 4.12 What is out of this row

| excluded | the row that holds it |
| :--- | :--- |
| `TIMESTAMP` on a lane: the registry's admission, the Arrow vector, the coverage column, the conformance suite, the first fused cells | 656 |
| any fused `ltz` cell or cast pair | 656, 657, 667 to 671, 690 |
| the nanosecond types in the census, and `timestamp_nanos` and `unix_nanos` | 661, 675 |
| the lists as one file an entry | 653 |
| `try_abs` and `try_negative` fused | 672 |
| `VarkaZoneSweepSuite`, which the proof column names | 684 |
| a fused cell of a date function over a timestamp (4.7, rule 4) | 672 for `ntz`, 668 for `ltz` |
| closing milestone 9's census | 677 |

### 4.13 Open questions for the owner

The plan is written on the first answer of each. *27 November 2026:* the owner answered
questions 1, 3 and 4 with the first answer of each (the tag, accept, and yes), and then
approved the plan as written, so the first answer of questions 2, 5 and 6 stands too.

1. **Where "its close" is read.** Recommended: the tree of the tag `varka-m8`. The other
   reading is the merge of the close's documents, which by `m8/VARKA-354.md` changes no
   generated file, so the two give the same five files.
2. **The new entries are curated by their declared input types.** Recommended: yes, the
   census's rule. Then `unix_seconds`, `unix_millis` and `unix_micros` admit `ntz` beside
   `ltz`, and `convert_timezone` is a `TIMESTAMP_NTZ` entry, though `m9/PLAN.md` 2.4 lists it
   under `TIMESTAMP_LTZ`. The other reading lists `ltz` alone and keeps item 3's denominator.
3. **`cast` and `try_cast` lose three fused sources when `ltz` enters** (4.10). Recommended:
   accept it; it is the counting rule of `m8/PLAN.md` 2.1 and it heals with row 690.
4. **An identity over a type no kernel reads is not fused** (4.9). Recommended: yes. The other
   way the registry names a conformance owner for `TIMESTAMP` before it lands, and `ltz` reads
   1 fused pair before any kernel exists.
5. **A date function over a timestamp** (`date_add`, `add_months`, `datediff`, `unix_date`).
   Milestone 8's census lists no `ntz` for them, though Spark casts a timestamp to their
   `DATE` parameter. Recommended: keep that reading for `ltz`; a row that fuses one adds it.
   *27 November 2026:* the owner chose the other answer, after he counted them for the
   nanosecond types (`VARKA-660.md` 13): the seven date functions, these four and `date_diff`,
   `dateadd` and `date_sub`, count for `ntz` and `ltz` in milestone 9's census (4.7, rule 4).
6. **Milestone 8's four lines leave `sql/varka/.gitattributes`.** Recommended: yes; a closed
   file is never merged or regenerated.

## 5. The parts

Two pull requests, in this order. The lead merges this plan to `sql/varka/plans/m9/VARKA-654.md`
before part a starts; both parts cite it and write into it. They cannot be built in parallel:
part b edits the files part a creates. Part a needs no other row of milestone 9; part b can be
built beside rows 655, 678 and 679, which touch none of its files.

| part | title | branch | needs | gives | counted lines |
| :--- | :--- | :--- | :--- | :--- | ---: |
| a | `[VARKA-654] Read and write the census by milestone, milestone 8's frozen at its close` | `varka-654a-census-by-milestone` | the tag `varka-m8` | `sql/varka/plans/m9/`'s five files; `--milestone`; `VarkaCensusFiles`; every reader on milestone 9's files. Every later row of milestone 9 reads it | about 560 |
| b | `[VARKA-654] Bring TIMESTAMP_LTZ into the census and the cast matrix` | `varka-654b-ltz-census` | part a | the census type `ltz`, its 70 pairs each with its row, the 23 cast pairs, the 16 names in scope. Rows 656, 657, 667 to 671, 688 and 690 read it | about 350 |

The code blocks of section 4 are wrapped for this page; `ruff format` gives the script its
committed form. Both sheets follow `sql/varka/plans/m8/BUILD_SHEETS.md`: its environment (the
three exports), the lock (`flock "$VARKA_HEAVY_LOCK"` around a plain `build/sbt`;
`dev/varka_regen_generated.sh` bare), no kill by pattern and no `git stash`.

**Nothing of this row starts before the tag `varka-m8` exists:** not part a, not the script's
change alone, not `VarkaCensusFiles`. Part b needs part a.

**Where a pasted block goes.** Every code block of this file is indented four columns for the
page, and eight where it sits inside a numbered item. That page indent is removed; the block's
first line then goes at the column below, and its other lines keep their offset from it.

| block | its first line goes at column |
| :--- | :--- |
| 4.2: `class Milestone` down to `NEW = MILESTONE.new`; the four functions with `CENSUS_FILES_JAVA`, `READERS` and `READER_NAMES` | 0: module level, with two blank lines before `class` and before each `def`, as `ruff format` leaves them |
| 4.2, item 5: the three insertions into `main` | 4: inside the function, at the column of the line each follows. A line that begins `# immediately` is an instruction of this plan: do not paste it |
| 4.3: `VarkaCensusFiles`; 5.1 step 6: the `census_inputs+=` line | 0 |
| 4.8: the `if "ltz" in (src, dst):` block of `legal`, and `legal`'s docstring | 4 |
| 4.8: the two lines in `cast_kind` | 4: the function's own level, **not** the 8 of the `return "identity", ""` line above them. Nested under that line they would never run |
| 4.8: `def ltz_cast_kind`; the comment, `ZONE_PROOF` and `def cast_proof` | 0 |
| 4.8: the `if "ltz" in lanes:` block of `cast_rows`, with its comment | 4 |
| 4.9: the comment and `READ` | 0; in `pair_credit`, the docstring lines and the `if` line at 4 and its `return` at 8. The `...` of that block stands for the function's unchanged lines |
| 5.2 steps 5 and 6: the two Java tests | 2: a member of the class |
| 5.2: the script of step 4, and every Python block of the command lists | 0 |

Where a premise
prints something else than the sheet says, the table under it says what the builder does: a rule
where one decides the case, and a stop only where the table says "the part ends". A stop is a
report of the command and its output; the builder asks nothing and builds nothing more. Below,
`M8=sql/varka/plans/m8`, `M9=sql/varka/plans/m9`, `T=target/varka-654` (make it; `target/` is
ignored) and `B=$(git merge-base HEAD origin/master)`. The shell is zsh, which does not split an
unquoted variable: write each `for f in ...` list out, as below.

### 5.1 Part a: the census by milestone

**B0, the premises.**

    test -f sql/varka/plans/m9/VARKA-654.md && echo merged              # merged
    git remote | while read r; do git fetch -q "$r" tag varka-m8 2>/dev/null; done
    git tag -l varka-m8                                                 # varka-m8
    git ls-files 'sql/varka/plans/m*/census*' 'sql/varka/plans/m*/casts*' | wc -l   # 5
    git grep -c -E "plans/m8/(census|casts)" -- .github README.md dev docs sql/varka/.gitattributes
                                                                        # the ten lines of fact 2
    git grep -c '"plans", "m8"' -- sql dev                              # the seven lines of fact 3
    python3 dev/varka_census.py --check; echo $?                        # 0
    git branch --list 'varka-654a*'                                     # nothing
    for f in census.tsv census_curated.txt casts.tsv casts_fused.tsv casts_suites.tsv; do
      git diff --quiet varka-m8 -- $M8/$f || echo "moved since the close: $f"; done
                                                                        # nothing, or the names

The second line fetches the tag from every remote: the close sets it on master and a clone may
not have it. Milestone 8's close also edits `README.md` and `docs/sql-varka.md`
(`m8/VARKA-354.md` 3, step 4), so the counts and the sentences this sheet quotes may have moved.

| premise | it prints | what the builder does |
| :--- | :--- | :--- |
| 1, the plan | nothing | the part ends: report "the plan is not on master" |
| 3, the tag | nothing, after the fetch | the part ends: report "no remote has the tag `varka-m8`; milestone 8 is not closed" |
| 4, the files | `10`, and `git ls-files $M9/census.tsv` prints the path | part a is merged: go to 5.2 |
| 4, the files | any other number | the part ends: report the list (`git ls-files` without `wc`). Row 653 has split a list, and this plan is cut for one file a list |
| 5, the paths | a file or a count that fact 2 does not have | rule R1 below, for each line `git grep -n` shows beyond fact 2 |
| 5, the paths | a file of fact 2 is missing or its count is lower | that reader is gone: leave it out of 4.3's edits and of `READERS` |
| 6, the path parts | a line that fact 3 does not have | it is a reader if the string after `"m8"` is one of the five file names or a variable that holds one: rule R1. If it is `"results"`, `"PLAN.md"` or a `VARKA-<n>.md`, it is not: leave it |
| 7, the check | not `0` | the part ends: report its output. Master is red, which comes first (`sql/varka/AGENTS.md`) |
| 8, the branch | a branch | the part ends: report its name; someone has started |
| 9, the files at the close | one or more names | no stop: step 2 handles it. Name them in the description |

**R1, a reader this plan does not name.** (1) In the path, `m8` becomes `m9`; in Java or Scala
test code the path becomes `VarkaCensusFiles.of(home, name)`. (2) A file that still names a path
after step (1) joins `READERS`, among the path strings, which are in alphabetical order (the
constant `CENSUS_FILES_JAVA` stays last); a test class that now goes through `VarkaCensusFiles`
names no path and does not join. (3) The description names it. One exception, a record: a
sentence of `README.md` or `docs/sql-varka.md` that tells of milestone 8 in the past (it says
"milestone 8" and "closed", "ended" or "at its close") keeps `m8`, and the builder re-wraps it so
that the words `milestone 8`, in lower case as `reader_errors` tests them (a sentence that begins
"Milestone 8" is reworded to hold them), are on the line that holds the path; `reader_errors`
lets such a line stay. Files under `sql/varka/plans/` and `sql/varka/skills/`, and
`CHANGELOG.md`, are records: they are in no premise, and no path in them changes.

**R2, a sentence the close reworded.** Step 7 quotes three lines of prose. If a quoted line is
not in its file, the builder finds its line by the path it holds, and changes on that line only
`m8` to `m9`:

| the quoted line | found by |
| :--- | :--- |
| `README.md`, the census sentence | `grep -n "plans/m8/census.tsv" README.md` |
| `docs/sql-varka.md`, the sentence above the support table | `grep -n "plans/m8/census.tsv" docs/sql-varka.md`, the hit that is not between the markers |
| `docs/sql-varka.md`, the sentence on Spark's cast suites | `grep -n "plans/m8/casts_suites.tsv" docs/sql-varka.md` |

Never edit a line between `<!-- BEGIN generated support table -->` and `<!-- END generated
support table -->`: step 9's run of the script rewrites that block, its paths included.

**An old line of the script or of a Java or Scala file that is not found exactly once.** 4.2
and 4.3 quote each old line; each is in its file once on master `a0e3a62cf2b`. If
`grep -c -F -- '<the line>' <file>` does not print `1` at the build, the part ends: report the
line and the count. No rule decides a moved line of code.

**A command of the list below that prints something else.** The builder reads the step that
the command checks again, corrects its own edit and reruns the command. If the output still
differs after that, the part ends: report the command and its output.

**The steps.**

1. `mkdir -p $T; python3 dev/varka_census.py --summary > $T/before.txt`.
2. Make milestone 9's files from the branch's base, then put milestone 8's at its close:

        for f in census.tsv census_curated.txt casts.tsv casts_fused.tsv casts_suites.tsv; do
          cp $M8/$f $M9/$f; git checkout varka-m8 -- $M8/$f; done

   The checkout changes nothing unless B0's last command named the file. `$M9` exists: it
   holds the milestone's plans.
3. `sha256sum $M8/census.tsv $M8/census_curated.txt $M8/casts.tsv $M8/casts_fused.tsv
   $M8/casts_suites.tsv`: the five digests are the values of `MILESTONES[8].closed`.
4. `dev/varka_census.py`: every change of 4.2, with the three doctests, which are inside the
   functions as 4.2 prints them.
5. `VarkaCensusFiles.java`, new, as 4.3 has it. Then the three Java readers of 4.3.
6. The other ten readers of 4.3, each the edit its line states, and each reader that rule R1
   added. In `dev/varka_precommit.sh`
   the second line of the Java pattern of `census_inputs` becomes:

        census_inputs+='/Varka([A-Za-z]*Conformance(Suite)?|CensusFiles)\.java'

7. `$M9/census_curated.txt`: the two header lines of 4.3. Each old text is on one line of the
   file. In `docs/sql-varka.md` the two prose lines are, today, "types
   (`sql/varka/plans/m8/census.tsv`), which lists every function and operator whose inputs and"
   and "  per form and mode is recorded in `sql/varka/plans/m8/casts_suites.tsv`,"; in `README.md`,
   "(`sql/varka/plans/m8/census.tsv`, summarised by `dev/varka_census.py --summary`)". In each,
   `m8` becomes `m9` (rule R2 if the close reworded one).
8. `VarkaCoverageSuite.scala`: in the comment of `censusType`, "in milestone 8's census and
   cast matrix" becomes "in the census and the cast matrix"; in the comment of `castsOf`,
   "`m8/casts.tsv`" becomes "`casts.tsv`".
9. `python3 dev/varka_census.py > /dev/null`: it rewrites `$M9/census.tsv`, `$M9/casts.tsv`
   and the docs block.
10. `sql/varka/plans/m9/PLAN.md`, row 654's cell: `**Planned**` becomes `**Started**` with one
    clause, "(part a, the census by milestone, #<number>)". This plan's section 12 gets part
    a's outcome: the three numbers command 5 printed, whether command 6 ran, and the size the
    check printed.

**The tests.**

| suite (language, module) | cases | input | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| the doctest of `closed_errors` (Python, `dev/varka_census.py`) | a missing file, an equal file, a changed file | a temporary folder with one closed milestone of one file | one error, none, one error; the oracle is the sha256 of the text | a closed file rewritten by a merge, a regeneration or a hand |
| the doctest of `reader_errors` (Python) | a shell path of the current milestone; a path built from its parts naming another; the Java constant naming another; a reader naming none; a reader that is missing; a record line of a closed milestone | six temporary files | no line for the first and the last, one line for each of the others, as 4.2 prints them | a reader left on the old milestone when `CURRENT` moves, or a new reader added with a stale path |
| the doctest of `closed_summary` (Python) | a closed milestone of two types, one entry in scope and one out, one cast pair | a temporary folder with its two tables | the lines `in scope: 1 entries (1 classes)`, `  int     existing    0 / 0`, `  date    new         1 / 1`, `cast pairs over the 2 types: 4 ordered, 1 legal`, and no decimal line; the oracle is the tables | `--milestone` printing the current milestone's types or split, or the live coverage table's lines, for a closed one |
| the script's existing doctests (Python) | all, unchanged | | pass | a change of the rendering in a refactoring that should move no byte |
| `VarkaCastMatrixSuite` (Java, catalyst), unchanged tests | "casts.tsv's legality agrees with Cast over every ordered pair, in every mode"; "casts_fused.tsv is what the compiler fuses, over every form of every legal cast"; the two planted ones | milestone 9's two files through `VarkaCensusFiles` | pass; the oracle is `Cast` and the compiler | the suite reading a folder the script does not write |
| the 14 `Varka<Type>ConformanceSuite` classes (Java, catalyst), unchanged | all | milestone 9's three tables through `VarkaCensusFiles` | the same passes and cancels as on the base | the harness reading milestone 8's frozen tables, and so never seeing a later claim |
| `VarkaCastWithAnsiOffSuite`, `VarkaCastWithAnsiOnSuite`, `VarkaTryCastSuite` (Scala, sql/core), unchanged | all | milestone 9's record through `VarkaSuiteRecord.plans` | pass, the record unmoved | the record compared with, or written to, the frozen folder |

**The planted faults**, each run by hand and reverted; each must fail the named check.

| plant | the check that must fail | with |
| :--- | :--- | :--- |
| `echo x >> $M8/casts.tsv` | `python3 dev/varka_census.py --check` exits 1 | `census: sql/varka/plans/m8/casts.tsv is not as milestone 8's close left it; restore it with: git checkout varka-m8 -- sql/varka/plans/m8/casts.tsv` |
| `MILESTONE = "m8"` in `VarkaCensusFiles.java` | the same, exits 1 | `census: sql/catalyst/.../VarkaCensusFiles.java names milestone 8's census files, and the census is milestone 9's` |
| `m9` back to `m8` in `dev/varka_nightly.sh` | the same, exits 1 | `census: dev/varka_nightly.sh names milestone 8's census files, and the census is milestone 9's` |

**The commands, in order, each with what it must print.**

    # 1. the doctests, and the check
    python3 dev/varka_census.py --self-test; echo $?                    # 0
    python3 dev/varka_census.py --check; echo $?                        # 0
    # 2. four of milestone 9's files are the base's milestone 8 files, byte for byte
    for f in census.tsv casts.tsv casts_fused.tsv casts_suites.tsv; do
      git show $B:$M8/$f | cmp - $M9/$f && echo "same $f"; done         # four lines "same ..."
    # 3. the curated file differs in two comment lines
    git show $B:$M8/census_curated.txt | diff - $M9/census_curated.txt | grep -c '^[<>]'   # 4
    # 4. the summary is the same text as before the change
    python3 dev/varka_census.py --summary | diff $T/before.txt -        # nothing
    # 5. milestone 8's summary, from its files alone
    python3 dev/varka_census.py --milestone 8 --summary > $T/m8.txt; echo $?   # 0
    awk -F'\t' 'NR>1 && $4=="in"' $M8/census.tsv | wc -l; grep '^in scope' $T/m8.txt
                                                    # the same number twice
    awk -F'\t' 'NR>1 && $4=="in" && $10=="done"' $M8/census.tsv | wc -l
    grep '^entries fused for every' $T/m8.txt       # the same number twice
    tail -n +2 $M8/casts.tsv | wc -l; grep '^cast pairs' $T/m8.txt
                                                    # N, and "over the 13 types: 169 ordered, N legal"
    # 6. only where B0's last premise named no file: it is the old summary less the band lines
    grep -v "^decimal entries\|an unscaled long\|a lane pair" $T/before.txt | diff - $T/m8.txt   # nothing
    # 7. milestone 8 is held to its close, and is not rewritten
    python3 dev/varka_census.py --milestone 8 --check; echo $?          # 0
    git diff --stat varka-m8 -- $M8/census.tsv $M8/census_curated.txt $M8/casts.tsv $M8/casts_fused.tsv $M8/casts_suites.tsv   # nothing
    python3 dev/varka_census.py --milestone 8; echo $?                  # the "is closed" line, then 2
    python3 dev/varka_census.py --milestone 7 --summary; echo $?        # census: no closed milestone 7, then 2
    # 8. the docs moved in three lines, all paths
    git diff $B -- docs/sql-varka.md | grep -c '^[+-][^+-]'             # 6
    git diff $B -- docs/sql-varka.md | grep '^[+-][^+-]' | grep -vc 'plans/m[89]/'   # 0
    # 9. the other tools
    python3 dev/varka_regen_inputs.py --selftest                        # varka_regen_inputs: self-test passed
    python3 dev/varka_generated_merge.py --self-test; echo $?           # 0
    python3 dev/varka_generated_merge.py --list | grep -c plans/m9      # 4
    python3 dev/varka_generated_merge.py --list | grep -c plans/m8      # 0
    grep -c 'plans/m8' dev/varka_regen_generated.sh                     # 0
    grep -c 'want sql/varka/plans/m9/casts' dev/varka_regen_generated.sh   # 2
    dev/varka_regen_generated.sh --all --full --dry-run | grep -c 'VarkaCastMatrixSuite.*VarkaTryCastSuite'   # 1
    dev/varka_new_lane.py --check; echo $?                              # 0
    # 10. the three planted faults of the table above, one at a time, each reverted
    # 11. the JVM: compile, then the suites that read the files
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/Test/compile' 'sql/Test/compile'   # [success] twice
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaCastMatrixSuite'
                                    # Passed: Total 4, Failed 0, Errors 0, Passed 4
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *ConformanceSuite'
                                    # sbt's summary line holds "Failed 0, Errors 0"
    flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaCastWithAnsiOffSuite *VarkaCastWithAnsiOnSuite *VarkaTryCastSuite'
                                    # All tests passed.
    # 12. every regeneration writes what the files hold
    git status --short > $T/status-before.txt
    dev/varka_regen_generated.sh --all; echo $?                         # 0
    git status --short | diff $T/status-before.txt -                    # nothing
    # 13. the house's checks
    ruff format dev/varka_census.py; ruff check dev/varka_census.py     # All checks passed!
    ./dev/lint-java; ./dev/lint-scala                                   # both pass
    python3 dev/varka_msg_check.py size --base origin/master            # a count under 1000

**Done when** every command prints as above; the pull request's description says what the
author's `/code-review high` found, names each reader rule R1 added and each file B0 found
moved since the close; and it states that no test time is added (the check hashes five small
files and reads eleven).

**Not here.** No main source (`git diff --stat $B -- sql/catalyst/src/main sql/core/src/main`
prints nothing). No
change of a type, an exclusion, a curated entry or `legal()`. No edit of a file under `$M8`.
No edit of a plan of milestone 8, which cites its files as records. No `git mv`.

**For a branch that is open when part a merges.** Say this in the description, and in a new
`##` section of `sql/varka/skills/working-in-this-repo.md`, "The census is by milestone: a
closed milestone's files are a record", after which `dev/varka_toc.py SKILLS.md --from
sql/varka/skills` rewrites the index. After merging master, a branch that edited milestone 8's
curated file moves its edit and restores the closed files:

    git diff $(git merge-base HEAD origin/master) HEAD -- sql/varka/plans/m8/census_curated.txt \
      | sed 's#plans/m8/#plans/m9/#g' | git apply
    for f in census.tsv census_curated.txt casts.tsv casts_fused.tsv casts_suites.tsv; do
      git checkout origin/master -- sql/varka/plans/m8/$f; done
    dev/varka_regen_generated.sh --all

Only the five files are restored: the branch's edits of milestone 8's plans stay. Where the
patch does not apply, the branch's lines are typed into milestone 9's file by hand.

### 5.2 Part b: `ltz` in the census and the cast matrix

**B0, the premises.**

    test -f sql/varka/plans/m9/VARKA-654.md && echo merged              # merged
    grep -n "^CURRENT = 9" dev/varka_census.py                          # one line: part a merged
    git ls-files sql/varka/plans/m9/census_curated.txt                  # the path
    grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f3,5              # "-" and "declined"
    grep -c '": LTZ' dev/varka_census.py                                # 20
    grep -c ",ltz\|ltz," sql/varka/plans/m9/census_curated.txt           # 0
    git grep -n "census's thirteen" -- sql/catalyst/src sql/core/src dev   # two lines, both in VarkaCastMatrixSuite
    python3 dev/varka_census.py --check; echo $?                        # 0
    git branch --list 'varka-654b*'                                     # nothing

| premise | it prints | what the builder does |
| :--- | :--- | :--- |
| 1, 2 or 3 | nothing | the part ends: report "part a is not merged" |
| 4, the registry | `ltz` in the first field | part b is merged: the row is done. Report it |
| 4, the registry | `-` and `owed` or `on_lane` | the part ends: report "row 656 merged before part b" |
| 5, the names | another count | no stop. A line of `NAME_EXCLUSIONS` that holds `LTZ` and is not one of the 20 names of 4.6 stays excluded: its `LTZ` becomes the new constant `ZONED = "TIMESTAMP_LTZ: not curated when row 654 brought the type in"`, defined under `NANOS`, and the description names it. A name of 4.6 that is gone from the dict is skipped, and the script's line for it is left out of `NEW` |
| 6, the curated file | not `0` | the script has run: `git checkout -- sql/varka/plans/m9/census_curated.txt`, then go on |
| 7, the message | nothing | the close reworded both lines: step 6's two text edits are done already; skip them and say so in the description |
| 7, the message | a line outside `VarkaCastMatrixSuite` | a test asserts on the message: reword that assertion with step 6's new text, and name it in the description |
| 8, the check | not `0` | the part ends: report its output; master is red |
| 9, the branch | a branch | the part ends: report its name |

**R2 for part b, a sentence the close reworded.** Step 8 quotes two sentences of prose. If a
quoted line is not in its file, the builder finds the sentence by
`grep -n "plans/m9/census.tsv" README.md docs/sql-varka.md`, taking in `docs/sql-varka.md` the
hit that is not between the markers `<!-- BEGIN generated support table -->` and `<!-- END
generated support table -->` (never edit a line between them). In that sentence two things
change and no other: the word or numeral that counts the census's types becomes one greater
(today "thirteen" becomes "fourteen"), and where the sentence lists the types, `TIMESTAMP` joins
the end of the list with the words "which no kernel reads yet". A count in any other sentence
counts the types a kernel reads, and stays.

**An old line of the script or of a Java file that is not found exactly once,** and **a command
that prints something else:** as in 5.1. The one planned exception is step 7's second command,
which fails two named tests.

**The steps.**

1. Before any edit:

        mkdir -p $T; python3 dev/varka_census.py --summary > $T/before.txt
        cp $M9/census.tsv $T/census-before.tsv; cp $M9/casts_suites.tsv $T/suites-before.tsv
        awk -F'\t' '$1=="cast"||$1=="try_cast"{n=split($8,a,","); for(i=1;i<=n;i++) if (a[i]!="ym"&&a[i]!="dt"&&!(a[i]=="bool"&&$1=="try_cast")) k++} END{print k+0}' $M9/census.tsv > $T/lost.txt

   `$T/lost.txt` is the number of fused sources that `cast` and `try_cast` will lose: each
   source they count as fused today that gains a legal pair to `ltz` (4.10). It is 3 on master
   `a0e3a62cf2b`.
2. `VarkaTypes`, the `TIMESTAMP` case: the line `case TIMESTAMP -> new Info(id, null,
   "TIMESTAMP",` becomes `case TIMESTAMP -> new Info(id, "ltz", "TIMESTAMP",` (4.5). Nothing
   else in the file.
3. `dev/varka_census.py`: 4.5 (the milestone's entry, `SIMPLE_COLUMN_TYPES`, the column count,
   the comment), 4.6 (`LTZ` deleted, `NANOS`, the 20 names, `CAST_ALIASES`), 4.8 (`legal` with
   its doctests, `ltz_cast_kind`, `ZONE_PROOF`, `cast_proof`, `cast_rows`) and 4.9 (`READ`,
   `pair_credit` with its doctest).
4. `$M9/census_curated.txt`: the header line of 4.5 by hand. Then save the script below as
   `$T/ltz_curated.py` and run
   `python3 $T/ltz_curated.py $M9/census_curated.txt | tee $T/curated.txt`. On master
   `a0e3a62cf2b` it prints one line, `69 entries edited, 27 of them no longer done, 16 added; 0
   by default, 0 skipped, 0 absent`; at the build the numbers are the close's, and each
   `default:`, `skipped:` or `absent:` line above the last goes into the description (4.7).
   The script is not committed. It exits non-zero in two cases only, each with its remedy in
   its message: the file already holds `ltz` (restore the file and run once), and a line with
   a wrong number of fields (not reachable while B0's check passes).
5. `VarkaTypeConformance`: in `CAST_TESTS`, `"int", "ntz",` becomes `"int", "ltz", "ntz",`,
   re-wrapped within 100 columns; and this test comes in after the method `castToInt` and
   before `castToTimestampNtz`:

        @Test
        @DisplayName("the cast to TIMESTAMP, in LEGACY, ANSI and TRY")
        void castToTimestamp() {
          castTo("ltz");
        }

6. `VarkaCastMatrixSuite`: the message "casts.tsv names a type outside the census's thirteen"
   becomes "casts.tsv names a type outside the census's types"; in the class comment, "every
   ordered pair of the census's thirteen types instead" becomes "every ordered pair of the
   census's types instead" (each old text is on one line); and one test, after the method
   `plantedLegality`, with one new import, `import static
   org.junit.jupiter.api.Assertions.assertNull;`, after the import of `assertFalse`:

        @Test
        @DisplayName("TIMESTAMP's pairs are the 23 Cast allows, 21 of them under ANSI and TRY")
        void timestampPairs() throws IOException {
          Map<String, Legality> file = committed();
          long pairs = 0;
          long ansi = 0;
          for (var e : file.entrySet()) {
            if (List.of(e.getKey().split(">")).contains("ltz")) {
              pairs++;
              ansi += e.getValue().ansi() ? 1 : 0;
            }
          }
          assertEquals(23, pairs);
          assertEquals(21, ansi);
          assertEquals(new Legality(true, false, false), file.get("bool>ltz"));
          assertEquals(new Legality(true, false, false), file.get("ltz>bool"));
          assertEquals(new Legality(true, true, true), file.get("ltz>ltz"));
          assertNull(file.get("ltz>ym"));
          assertNull(file.get("dt>ltz"));
        }

7. Regenerate, in this order. The order matters once: the suite writes the probe before the
   script can write `casts.tsv`, so the first run of the suite fails three tests by design.

        VARKA_TYPES_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaTypesSuite'
        VARKA_CASTS_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaCastMatrixSuite'
        python3 dev/varka_census.py > /dev/null
        dev/varka_regen_generated.sh --all
        python3 dev/varka_census.py --summary > $T/after.txt

   The second command writes `$M9/casts_fused.tsv` and fails exactly three tests, `legality`,
   `plantedLegality` and `timestampPairs`: `casts.tsv` has no `ltz` pair yet, and the planted
   test reads the same file (`VARKA-661.md` 11). With any other failure the part ends:
   report that test's output (risk 6). The third
   writes `$M9/census.tsv`, `$M9/casts.tsv` and the docs block. The fourth runs every suite
   that writes a file, the three cast suites of sql/core among them, and the census again; it
   must exit 0. The fifth keeps the summary for the commands below.
8. The prose, by hand. Each sentence spans two lines of its file, so each line is quoted
   apart; search for one line at a time.

| file | the old line | the new line |
| :--- | :--- | :--- |
| `docs/sql-varka.md` | `surface fuses today. They are read from the census of Spark's function registry over the thirteen` | `surface fuses today. They are read from the census of Spark's function registry over its fourteen` |
| `docs/sql-varka.md`, the next line | begins ``types (`sql/varka/plans/m9/census.tsv`), which lists`` | unchanged |
| `README.md` | `tracks thirteen types: BOOLEAN, TINYINT, SMALLINT, INT, BIGINT, FLOAT, DOUBLE,` | `tracks fourteen types: BOOLEAN, TINYINT, SMALLINT, INT, BIGINT, FLOAT, DOUBLE,` |
| `README.md`, the next line | `DECIMAL, DATE, TIME, TIMESTAMP_NTZ and the year-month and day-time intervals. For` | `DECIMAL, DATE, TIME, TIMESTAMP_NTZ, the year-month and day-time intervals, and TIMESTAMP,` and a new line after it, `which no kernel reads yet. For` |
| `VarkaTimeCompiler.java` | ``      // (VARKA-340). This cast is not built over it yet (`m8/casts.tsv` has the pair`` | `      // (VARKA-340). This cast is not built over it yet (the cast matrix has the pair` |
| `VarkaTimeCompiler.java`, the next line | begins `      // unclaimed), so it declines` | unchanged |

   The other "thirteen" of `docs/sql-varka.md` and `README.md` count the types a kernel reads
   and stay. Where an old line is not found, rule R2 for part b applies to the two documents;
   in `VarkaTimeCompiler.java` the only change is that the comment no longer says `m8/`.
9. `sql/varka/ADDING_A_TYPE.md`, step 4 ("**4. Teach the compiler.**"), a paragraph of its own
   immediately before the line ``Check: `build/sbt 'catalyst/testOnly *VarkaTypesSuite
   *VarkaExpressionCompilerSuite'`.``, wrapped within 100 columns: "A type new to
   the census gets its census name in its registry entry first, while its admission still
   declines it, and the census's side in the same pull request: the type in the current
   milestone's `types` and `new` in `dev/varka_census.py`, its pairs in `legal`, `cast_kind`,
   `cast_proof` and `cast_rows`, a cast test in `VarkaTypeConformance`, and its cells in the
   milestone's `census_curated.txt`, each naming the row that owes it. `plans/m9/VARKA-654.md`
   is the worked case. The lane then comes in a pull request that turns the admission."
10. `sql/varka/plans/m9/PLAN.md`, row 654's cell: `**Started**` becomes `**Done**`, with the
    two pull request numbers and the count N of command 3. In its 1.2, item 1 spans two lines:
    "1. **G1:** at least three quarters of `TIMESTAMP_LTZ`'s (entry, type) pairs fused (about 66"
    and "   entries are expected to admit it)."; the second line becomes "   entries are
    expected to admit it; N admit it since row 654, <the date>).", re-wrapped within 100
    columns. This plan's section 12: the outcome, each prediction of section 7
    scored. Part a's section of `sql/varka/skills/working-in-this-repo.md` gains two
    sentences: how a type enters the census (4.5 to 4.9), and how a milestone's census is
    closed (the comment above `CURRENT`).

**The script of step 4** (one-off; it rewrites its argument in place; 4.7 has its rules):

    #!/usr/bin/env python3
    """One-off: bring TIMESTAMP_LTZ into milestone 9's curated census (VARKA-654, part b).

    usage: ltz_curated.py <census_curated.txt>   # rewrites the file in place
    Not committed. An entry that no list names gets the row of its kind (default_row) and is
    printed; so is a listed entry that no longer admits a timestamp, or is gone.
    """
    import sys

    OWNER = {}
    for row, names in {
        "656": "coalesce if ifnull nullif nvl nvl2 when between isnull isnotnull equal_null in <=> = =="
        " > >= < <= greatest least",
        "667": "hash xxhash64 in_set known_not_null try_eval",
        "669": "cast try_cast boolean tinyint smallint int bigint float double decimal date time",
        "657": "hour minute second",
        "668": "day dayofyear dayofmonth last_day month next_day quarter trunc dayofweek weekday"
        " weekofyear year extract date_part datepart time_bucket",
        "671": "ntz_minus_ntz datetime_sub ntz_plus_dt ntz_plus_ym timestampadd",
    }.items():
        for n in names.split():
            OWNER[n] = row
    UNDONE, DEFAULTED, SKIPPED = [], [], []
    ZONE_FREE = {"656", "667"}  # their kernels read no zone: what is left is plumbing
    # The date functions that Spark takes over a timestamp through a cast to DATE. Milestone 8's
    # census lists no timestamp for them; milestone 9's counts them for both microsecond timestamps
    # (VARKA-660.md 16). ntz is row 672's, the existing temporal types' tail; ltz is row 668's, with
    # the fields.
    DATE_FUNCTIONS = "add_months datediff date_diff date_add dateadd date_sub".split()
    NEW = """\
    # TIMESTAMP_LTZ's own entries, in scope since milestone 9 (m9/PLAN.md 2.4, VARKA-654).
    timestamp_seconds|NUM|ltz||semantics|667|seconds since the epoch to microseconds, reading no zone; a fraction truncates, an overflow raises
    timestamp_millis|INTEGRAL|ltz||semantics|667|milliseconds since the epoch to microseconds, reading no zone; an overflow raises
    timestamp_micros|INTEGRAL|ltz||plumbing|667|the long relabelled; reads no zone
    unix_seconds|ntz,ltz|bigint||plumbing|667|floor division of the microseconds by a million; reads no zone
    unix_millis|ntz,ltz|bigint||plumbing|667|floor division of the microseconds by a thousand; reads no zone
    unix_micros|ntz,ltz|bigint||plumbing|667|the long relabelled; reads no zone
    from_utc_timestamp|ltz|ltz||semantics|670|a literal zone; a zone column is out of scope (m9/PLAN.md 1.3)
    to_utc_timestamp|ltz|ltz||semantics|670|a literal zone; a zone column is out of scope (m9/PLAN.md 1.3)
    convert_timezone|ntz|ntz||semantics|670|literal zones, a TIMESTAMP_NTZ in and out; a zone column is out of scope (m9/PLAN.md 1.3)
    date_trunc|ltz|ltz||semantics|670|truncated in the session zone; a unit of a second or finer reads no zone
    months_between|ltz|double||semantics|670|both ends in the session zone; roundOff a literal
    make_timestamp|date,time,int,dec|ltz||semantics|670|the date and time form and the six-field form, in the session zone or a literal zone
    try_make_timestamp|date,time,int,dec|ltz||semantics|670|null instead of an error
    make_timestamp_ltz|date,time,int,dec|ltz||semantics|670|as make_timestamp, always TIMESTAMP_LTZ
    try_make_timestamp_ltz|date,time,int,dec|ltz||semantics|670|null instead of an error
    timestamp|legal|ltz|matrix|semantics|669|the cast alias; its pairs are the cast matrix's
    """


    def split(line):
        return ["|"] + line[2:].split("|") if line.startswith("||") else line.split("|")


    def after_ntz(field):
        parts = field.split(",")
        if "ltz" in parts:
            sys.exit(f"already has ltz: {field}")
        at = parts.index("ntz") + 1
        return ",".join(parts[:at] + ["ltz"] + parts[at:])


    def default_row(fields, first):
        """The row that owes ltz for an entry no list names, by its kind: (row, kind). An implicit
        entry (`first` is 3) has its family in the third field."""
        types, result = fields[first].split(","), fields[first + 1].split(",")
        implicit = first == 3
        if types == ["legal"] or (implicit and fields[2] == "conversion"):
            return "669", "a cast"
        if "ntz" in types:
            if implicit or "ntz" in result:
                return "671", "timestamp arithmetic"
            return "668", "a field of a timestamp"
        if result in (["bool"], ["same"]):
            return "656", "a comparison, a null test or a choice"
        return "667", "a function that reads no zone"


    def edit(fields, first):
        """`fields` of one entry, `first` the index of its types field."""
        name = fields[0]
        types, result, need, rows, note = (first + i for i in (0, 1, 3, 4, 5))
        if name in DATE_FUNCTIONS and first == 1:
            if {"ntz", "ltz"} & set(fields[types].split(",")):
                sys.exit(f"already has a timestamp: {name}")
            fields[types] += ",ntz,ltz"
            if fields[need] == "done":
                fields[need] = "semantics"
                UNDONE.append(name)
            held = [r for r in fields[rows].split(",") if r]
            fields[rows] = ",".join(held + ["672", "668"])
            add = "a timestamp through its date, the cast to DATE under the node: ntz is row 672's,"
            fields[note] = (fields[note] + "; " if fields[note] else "") + add + " ltz row 668's"
            return fields
        listed = "ntz" in fields[types].split(",")
        reaches = listed or fields[types] in ("ALL", "legal")
        if not reaches:
            if name in OWNER:
                SKIPPED.append(name)
                print(f"skipped: {name} is on a list, and its types {fields[types]} admit no timestamp")
            return fields
        if name in OWNER:
            row = OWNER[name]
        else:
            row, kind = default_row(fields, first)
            DEFAULTED.append(name)
            print(f"default: {name} -> row {row} ({kind})")
        if listed:
            fields[types] = after_ntz(fields[types])
            if "ntz" in fields[result].split(","):
                fields[result] = after_ntz(fields[result])
        if fields[need] == "done":
            fields[need] = "plumbing" if row in ZONE_FREE else "semantics"
            UNDONE.append(name)
        held = [r for r in fields[rows].split(",") if r]
        if row not in held:
            fields[rows] = ",".join(held + [row])
        fields[note] = (fields[note] + "; " if fields[note] else "") + f"ltz is row {row}'s"
        return fields


    def main(path):
        out, section, seen = [], None, set()
        lines = open(path, encoding="utf-8").read().split("\n")
        if any("ltz" in line.split("|")[0] or ",ltz" in line for line in lines if line[:1] != "#"):
            sys.exit(f"{path} already holds ltz: restore it (git checkout -- {path}) and run once")
        for i, line in enumerate(lines):
            if line.startswith("[") and line.endswith("]"):
                if section == "registered":
                    # The new entries close [registered], above the blank lines before the next section.
                    k = len(out)
                    while out[k - 1] == "":
                        k -= 1
                    out[k:k] = NEW.rstrip("\n").split("\n")
                section = line[1:-1]
            elif line and not line.startswith("#") and section in ("registered", "implicit"):
                first = 1 if section == "registered" else 3
                fields = split(line)
                if len(fields) != first + 6:
                    sys.exit(f"line {i + 1}: {len(fields)} fields")
                before = list(fields)
                fields = edit(fields, first)
                if fields != before:
                    seen.add(fields[0])
                    line = "||" + "|".join(fields[1:]) if fields[0] == "|" else "|".join(fields)
            out.append(line)
        absent = sorted(set(OWNER) - seen - set(SKIPPED))
        for name in absent:
            print(f"absent: {name} is on a list and not in the file")
        open(path, "w", encoding="utf-8").write("\n".join(out))
        added = NEW.count("\n") - 1
        print(f"{len(seen)} entries edited, {len(UNDONE)} of them no longer done, {added} added;"
              f" {len(DEFAULTED)} by default, {len(SKIPPED)} skipped, {len(absent)} absent")


    if __name__ == "__main__":
        main(sys.argv[1])

**The tests.**

| suite (language, module) | cases | input | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| the doctests of `legal` (Python, `dev/varka_census.py`) | `ltz` with `bool`, `int`, `date`, `ym` and itself; the count of legal pairs; the count under ANSI | the fourteen types | `(True, False)`, `(True, True)`, `(True, True)`, `(False, False)`, `(True, True)`; 23; 21. The oracle is `Cast`, read in 4.8 | a wrong transcription that the next regeneration would write into `casts.tsv` before any JVM test runs |
| the doctest of `ltz_cast_kind` (Python) | `date` to `ltz` through the function and through `cast_kind`; `ltz` to `int` through `cast_kind` | the three pairs | `('local to instant', 'local to instant', 'floor division')` | the two lines of `cast_kind` nested under its identity block, where they never run and every `ltz` pair would take another type's kind |
| the doctest of `pair_credit` (Python) | an identity over a type outside `read` | `read={"date"}`, the pair `int` to `int` | `''` | a type no kernel reads counted fused through its alias |
| `VarkaCastMatrixSuite` (Java, catalyst): "casts.tsv's legality agrees with Cast over every ordered pair, in every mode", unchanged | every ordered pair of the fourteen types, every form of each | `$M9/casts.tsv` | pass; the oracle is `Cast.canCast`, `canAnsiCast` and `canTryCast` | `legal()` wrong for a pair of `ltz`, a form included: a `TIME(0)` and a `TIME(6)`, both decimals |
| the same suite: "TIMESTAMP's pairs are the 23 Cast allows, 21 of them under ANSI and TRY", new | the counts; `bool` both ways under LEGACY alone; the identity; no pair with an interval | `$M9/casts.tsv` | pass | the registry's census name and the census's type both dropped together, which every other test would pass |
| the same suite: "casts_fused.tsv is what the compiler fuses, over every form of every legal cast", unchanged | every legal cast of two forms, 28 of them with `TIMESTAMP` | a column `x` of type `TIMESTAMP`, and a cast to it from each form | 28 lines, each `declined` or `illegal`, and no exception; the oracle is the compiler | a compiler that raises, or fuses, over a type the registry declines |
| the 14 conformance suites (Java, catalyst): "the cast to TIMESTAMP, in LEGACY, ANSI and TRY", new through the base class | the type's cast to `TIMESTAMP` in each mode Spark admits | each suite's fixture: its nulls, both ends of the type, its special values | cancelled, naming rows 669 or 690 as owing, in 12 suites; cancelled as "casts.tsv has no cast" in the two interval suites; failed in none | a census type with no cast test, which would fail every cast test of every suite (fact 10); and a cast to `TIMESTAMP` that starts to fuse unrecorded, which this test then reports as drift |
| `VarkaTypesSuite` (Java, catalyst), unchanged | "sql/varka/types.tsv is the registry as it is"; "a column of an owed, declined or condition-only type declines naming its type" | the registry | pass: the type still declines with its reason | the census name arriving with a change of admission |
| the three cast suites of sql/core (Scala), unchanged | all | Spark's own cast tests | pass; the record's 19 lines read `ltz` | the record and the matrix naming one type by two names |

The row adds no proof file (it adds no lowering), no fuzz production (the fuzzers draw a type
from `sql/varka/fuzz/types.tsv`, where `TIMESTAMP` stays `n/a` until row 656), no coverage row
and no IR record, so no registry suite of an IR node applies.

**The planted faults**, each run by hand and reverted.

| plant | the check that must fail | with |
| :--- | :--- | :--- |
| in `legal`, make the `ltz` branch's second value `other in zoned + ["bool"]` | `python3 dev/varka_census.py --check` exits 1 in its doctests | `Expected: [(True, False), ...` and `Got: [(True, True), ...` |
| in `$M9/census_curated.txt`, `isnull`'s fused field gains `ltz` | the check exits 1 | `census: isnull: no coverage row has a IsNull node over a ltz operand` |
| delete the line that begins `unix_seconds\|` in the curated file | the check exits 1 | `census: unix_seconds (datetimeExpressions, UnixSeconds): neither curated nor excluded` |
| in `VarkaTypeConformance`, take `"ltz"` out of `CAST_TESTS` | `build/sbt 'catalyst/testOnly *VarkaIntConformanceSuite'` fails its cast tests | "the cast tests are one per census type: add a test for each census type the registry names" |

**The commands, in order, each with what it must print.** The two Python blocks are indented
here with the rest; typed, their lines and the closing `EOF` start at the first column (the
table of section 5).

    # 1. the doctests and the check
    python3 dev/varka_census.py --self-test; echo $?                    # 0
    python3 dev/varka_census.py --check; echo $?                        # 0
    # 2. the registry's table
    grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f3,5              # "ltz" and "declined"
    git diff $B -- sql/varka/types.tsv | grep -c '^[+-][^+-]'           # 2
    # 3. ltz's pairs: the count the curated file gives, and the census's
    awk -F'|' '/^\[/{s=$0} !/^#/&&s=="[registered]"&&NF>=7{t=$2} !/^#/&&s=="[implicit]"&&NF>=9{t=$4} t!=""{n+=(t=="ALL"||t=="legal"||(","t",")~/,ltz,/); t=""} END{print n}' $M9/census_curated.txt
                                                    # N: the script's first number plus 8
    grep '^  ltz' $T/after.txt | awk '{print $2, $3, $5}'   # new 0 N, the same N
    grep -c '^  ltz ' $T/after.txt                  # 1 (the line itself is "  ltz     new         0 / 77" on a0e3a62cf2b)
    # 4. the cast matrix and the probe
    awk -F'\t' '$1=="ltz"||$2=="ltz"' $M9/casts.tsv | wc -l             # 23
    awk -F'\t' '($1=="ltz"||$2=="ltz") && $4=="yes"' $M9/casts.tsv | wc -l   # 21
    awk -F'\t' '$1=="ltz"||$2=="ltz"' $M9/casts_fused.tsv | wc -l       # 28
    awk -F'\t' '$1=="ltz"||$2=="ltz"' $M9/casts_fused.tsv | grep -c 'fused'   # 0
    git diff $B -- $M9/casts_fused.tsv | grep -c '^-[^-]'               # 0: no line of another type moved
    grep '^cast pairs' $T/before.txt $T/after.txt   # "13 types: 169 ordered, L legal", then "14 types: 196 ordered, L+23 legal"
    grep -P "^ltz\tdate\t" $M9/casts.tsv | cut -f3-5,9,10               # yes yes yes, an empty fused_today, 657,669
    awk -F'\t' '$1=="ltz"||$2=="ltz"{print $6}' $M9/casts.tsv | sort | uniq -c
                                                    # eleven lines, each a count and a kind:
                                                    #   4 floor division      1 identity
                                                    #   3 instant to local    2 local to instant
                                                    #   1 rescale             2 rounding
                                                    #   5 scale up            1 select
                                                    #   1 session date        1 test
                                                    #   2 truncation
    # 5. the twenty names
    awk -F'\t' '$4=="out" && $5 ~ /TIMESTAMP with local/' $M9/census.tsv | wc -l   # 0
    grep -c "nanosecond timestamps" $M9/census.tsv                      # 2
    grep -P "^(current_timestamp|now)\t" $M9/census.tsv | grep -c "folded to a literal"   # 2
    grep '^in scope:' $T/before.txt $T/after.txt    # the second 16 entries above the first
    # 6. no fused claim of another type moved, but the ones cast and try_cast lose
    grep '(entry, type) pairs' $T/before.txt $T/after.txt; cat $T/lost.txt
                                                    # fused today: the first less the number of lost.txt
    python3 - <<'EOF'
    import sys
    a = {l.split("\t")[0]: l.split("\t") for l in open("target/varka-654/census-before.tsv").read().splitlines()[1:]}
    b = {l.split("\t")[0]: l.split("\t") for l in open("sql/varka/plans/m9/census.tsv").read().splitlines()[1:]}
    print(sorted(k for k in a if a[k][3] == "in" and a[k][7] != b[k][7]))
    EOF
                                                    # ['cast', 'try_cast'], or a part of it, and nothing else
    # 7. the entries no longer done
    grep '^entries fused for every' $T/before.txt $T/after.txt; cat $T/curated.txt
                                                    # the second is the first less the script's second number
    # 8. what rows 656, 657 and 688 will grep; a number that differs ends the part (4.7)
    grep -c "^hour|time,ntz,ltz|\|^minute|time,ntz,ltz|\|^second|time,ntz,ltz|" $M9/census_curated.txt   # 3
    grep -c 'TimestampAddInterval|datetime|ntz,ltz,dt|ntz,ltz|' $M9/census_curated.txt   # 1
    grep -c '^ntz_minus_ntz|SubtractTimestamps|datetime|ntz,ltz|dt|' $M9/census_curated.txt   # 1
    grep -c '"timestamp": "ltz"' dev/varka_census.py                    # 2: CAST_ALIASES and SIMPLE_COLUMN_TYPES
    grep -n '"ltz"' sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTypeConformance.java | wc -l   # 2: CAST_TESTS and castToTimestamp
    # 9. every ltz cell names a row of milestone 9
    awk -F'\t' 'NR>1 && $4=="in" && (","$6",")~/,ltz,/ && $11 !~ /(^|,)(65[67]|66[789]|67[01])(,|$)/' $M9/census.tsv | wc -l   # 0
    # 10. the suites' record: the 19 renamed lines and nothing else
    python3 - <<'EOF'
    old = open("target/varka-654/suites-before.tsv").read().splitlines()
    body = []
    for l in old[1:]:
        f = l.split("\t")
        if f[3] == "TIMESTAMP": f[1] = "ltz"
        if f[4] == "TIMESTAMP": f[2] = "ltz"
        body.append("\t".join(f))
    body.sort(key=lambda l: l.split("\t")[:6])
    new = open("sql/varka/plans/m9/casts_suites.tsv").read().splitlines()
    print(new == [old[0]] + body, sum("\tltz\t" in l for l in new))
    EOF
                                                    # True, and the count of renamed lines (19 on a0e3a62cf2b)
    # 11. what must not move
    git diff --stat $B -- sql/varka/coverage.json sql/varka/emitted_bytes.json sql/varka/width_audit.json sql/varka/fuzz/types.tsv sql/varka/plans/m8   # nothing
    python3 dev/varka_census.py --milestone 8 --check; echo $?          # 0
    dev/varka_new_lane.py --check; echo $?                              # 0
    # 12. the docs' cast tables have a column for each type
    awk '/BEGIN generated support table/,/END generated support table/' docs/sql-varka.md | grep -c '^| from .* `ltz` |$'   # 3
    awk '/BEGIN generated support table/,/END generated support table/' docs/sql-varka.md | grep -c '^| `TimestampType` (`ltz`) | plain | '   # 1
    awk '/BEGIN generated support table/,/END generated support table/' docs/sql-varka.md | grep -c '^| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |$'   # 3: fifteen columns
    # 13. the planted faults of the table above, one at a time, each reverted
    # 14. the JVM
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaCastMatrixSuite *VarkaTypesSuite'
                                    # Failed 0, Errors 0; the cast suite now has 5 tests
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *ConformanceSuite'
                                    # Failed 0, Errors 0; 14 lines "the cast to TIMESTAMP ... !!! CANCELED !!!"
    flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaCastWithAnsiOffSuite *VarkaCastWithAnsiOnSuite *VarkaTryCastSuite'
                                    # All tests passed.
    # 15. the house's checks
    ruff format dev/varka_census.py; ruff check dev/varka_census.py     # All checks passed!
    ./dev/lint-java; dev/varka_errorprone.sh                            # both pass
    python3 dev/varka_msg_check.py size --base origin/master            # a count under 1000
    dev/varka_precommit.sh --range origin/master..HEAD                  # no finding

**Done when** every command prints as above; the summary for milestone 9 prints `ltz`'s line; the
description lists the script's `default:`, `skipped:` and `absent:` lines under "Entries the plan
did not name" (or says there were none), and states the three fused sources `cast` and `try_cast`
lose and why, what the author's review found, and the test time added (one test in
`VarkaCastMatrixSuite`, 28 probes, one cancelled test in each of 14 suites).

**Not here.** No change of `TIMESTAMP`'s admission, lanes or conformance in `VarkaTypes`. No
`ltz` in any fused field. No coverage row, fixture column, Arrow arm, emit option or IR node.
No edit under `$M8`. No change of a default.

**Checks that run only in CI.** The option matrix and the width audit read no file of this
row and need no dispatch of their own; the pull request's Build runs the suites at the
defaults, and its lint job runs `./dev/varka_census.py --check` and the size check. Locally the
builder runs commands 1 to 15 first.

## 6. Tests, proofs, fuzzing and coverage

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| the doctests of `dev/varka_census.py`, run by `--check` and `--self-test` | `closed_errors`, `reader_errors` (part a); `legal` over `ltz`, `pair_credit`'s identity rule (part b); every doctest the script has | a closed file rewritten; a reader on the wrong milestone; a wrong transcription of `Cast` before any JVM runs |
| `VarkaCastMatrixSuite` (JUnit 5, `@VarkaSuite`, catalyst) | the legality of all 196 ordered pairs against `Cast`; the 23 pairs pinned; the probe of 393 form pairs against the compiler | a wrong `legal()`; the type dropped on both sides; a compiler answer that moved |
| `VarkaTypeConformance` through its 14 suites (JUnit 5, catalyst) | one cast test for each census type; a cast the registers owe must decline | a type with no cast test; a pair that fuses with no claim |
| `VarkaTypesSuite` (JUnit 5, catalyst) | `types.tsv` is the registry; `TIMESTAMP` declines by name | a registry edit that is more than a name |
| Spark's three cast suites under Varka (ScalaTest, sql/core) | the record of each bare cast's verdict, held to the matrix by the census check | the record written to, or compared with, another folder; a type under two names |
| `dev/varka_census.py --check` in the hook, the lint job, the push check and the regeneration | every file of the current milestone equals a fresh one; every closed file its digest; every reader the current folder | any of the above reaching master |

- **Proof files:** none. The row adds no lowering. The cast table's proof column names row
  684's sweep for the six pairs that read the zone; that suite is row 684's.
- **Fuzzing:** nothing draws `TIMESTAMP` until row 656 marks it present in
  `sql/varka/fuzz/types.tsv`. Row 684 draws the session zone.
- **JaCoCo and mutation:** not applied. The row's code is a Python script, held by its
  doctests and by the planted faults of 5.1 and 5.2, and a Java class of one constant.

## 7. Measurement

No benchmark section and no A/B arm: the row emits no kernel, and `emitted_bytes.json` does
not move in either part. Nothing runs on the EPYC runners for it.

**Predictions, registered 27 November 2026, before any run.** Each count is of master
`a0e3a62cf2b`; the rule beside it is what the build scores, by the named command.

| # | prediction | at `a0e3a62cf2b` | the rule at the build | scored by |
| ---: | :--- | :--- | :--- | :--- |
| P1 | part a moves no count and no byte of four files | summary identical; four `same` lines | the same | 5.1, commands 2 and 4 |
| P2 | milestone 8's summary from its files is the old summary less three lines | yes | yes, where no file moved since the close | 5.1, command 6 |
| P3 | `ltz` admits 77 pairs and fuses none | `  ltz     new         0 / 77` | 0, and the script's first number plus 8 | 5.2, command 3 |
| P4 | 23 cast pairs, 21 under ANSI and TRY; 129 legal pairs in all | yes | 23 and 21; the close's count plus 23 | 5.2, command 4 |
| P5 | the probe gains 28 lines, none fused, and no other line moves | 365 to 393 | plus 28 | 5.2, command 4 |
| P6 | the pairs fused fall by 3, in `cast` and `try_cast` alone | 588 to 585 | by the number of `$T/lost.txt` | 5.2, command 6 |
| P7 | 23 entries are no longer `done` | 85 to 62 | the script's second number | 5.2, command 7 |
| P8 | 16 entries come into scope and no name keeps the zoned reason | 182 to 198 | plus 16 | 5.2, command 5 |
| P9 | the suites' record changes by 19 renamed lines, re-sorted, with no verdict moved | yes | the transform of command 10 gives the file | 5.2, command 10 |
| P10 | `coverage.json`, `emitted_bytes.json`, `width_audit.json` and `fuzz/types.tsv` do not move | yes | the same | 5.2, command 11 |
| P11 | the probe compiles a cast over a `TIMESTAMP` column to a decline, and raises nothing | read, not run | the same | 5.2, step 7: two failures, no error |
| P12 | every one of the 14 conformance suites cancels its new test and fails nothing | read, not run | the same | 5.2, command 14 |
| P13 | part a is under 700 counted lines and part b under 500 | about 560 and about 350 | the same | the size check of each part |
| P14 | `VarkaCastMatrixSuite` stays under 5 s of test time with its fifth test and 28 more probes | the calibration table has this suite's first prediction (VARKA-328, 6.1 #2) far under its bound, so the bound is kept, not tightened | the same | sbt's time for the suite, in part b's outcome |
| P15 | `python3 dev/varka_census.py --check` stays under 5 s on the laptop after both parts | not timed: the laptop is shared | the same | `time` of the command, in part a's outcome |

P14 and P15 read the calibration table first (`python3 dev/varka_calibration.py`); each scored
prediction joins it by `dev/varka_calibration.py --add-row`.

## 8. Who builds it

Both parts are built at full strength. `m9/PLAN.md` 1.4 puts the row in "Before the fan-out",
and G5's cheaper models start "After the foundation", from row 658's template and ledger, which
do not exist when this row runs.

- **Part a** is mechanical and every step has a byte-level oracle. It still decides how every
  later row finds the census, and a slip freezes a wrong file as milestone 8's record.
- **Part b** is curation: 62 entries and 16 new lines, each a claim about Spark's types that
  the done-when of G1 and G3 is then counted on.

## 9. Risks

1. **Milestone 8's files move between its tag and part a.** A carried row's part may merge
   first. Held by B0's last command and step 2: `m9/` takes the moved files, `m8/` the tag's.
2. **The tag is missing or moves.** B0 stops on a missing tag. A moved tag changes the digests:
   the check then fails on master and names the file. The remedy is one of two, the owner's
   to choose in that pull request: restore the file, or record the new digest in `M8_CLOSED`.
3. **Open branches edit milestone 8's curated file.** `m9/PLAN.md` 3.2 counts 31 local branches
   of the carried rows. After part a each fails the check on its first merge of master, with
   the file's name; the recipe at the end of 5.1 moves the edit. Held by `closed_errors`.
4. **Row 653 changes the layout first.** Its lists become one file an entry. Part a's B0 then
   prints another count and the part ends there; this plan is then re-cut on 653's layout.
   It is the one stop of part a that no rule replaces, beside the missing tag and plan.
5. **The first regeneration of part b is red by design.** The builder may take the two
   expected failures for a fault, or miss a third. Held by step 7, which names the two tests.
6. **The probe raises over a `TIMESTAMP` column** instead of declining (P11, not run). Then the
   stop is a compiler fault of its own: report it; do not catch the exception in the suite.
7. **A conformance suite fails instead of cancelling** (P12, not run), for example where a
   suite's fixture type is not a form of the probe. The assertion names the missing form.
8. **The close's census holds an entry no list names,** or rewords a sentence this plan
   quotes. Held by the default rows of 4.7, which the script applies and prints, and by rules
   R1 and R2; the description lists each case for the owner's review.
9. **Two pull requests each bring a type into the census.** Row 656 must not merge before part
   b. Held by part b's B0 and by section 11.
10. **The curated edit is 141 changed lines of hand-curated data in one pull request.** A
   reviewer cannot read each by eye. Held by the script, whose rules are 4.7's three, and by
   commands 3, 6, 7 and 9, which count what it did.
11. **`ltz` reads `plain` in the docs' Lanes column until row 656.** It is the registry's
    storage name for a type with no lane, as `bool` reads `bitmap`. Stated in the description.
12. **The plan ages.** Every count is of `a0e3a62cf2b`; the sheets re-derive each. A premise of
    B0 that prints another reader or another count has its rule in the table under it.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md`:

- "The generated files every type row rewrites (`coverage.json`, `emitted_bytes.json`,
  `width_audit.json`, `types.tsv`, the census and cast tables, the docs blocks) are never merged
  by hand." Regenerate; never edit `census.tsv`, `casts.tsv`, `casts_fused.tsv`,
  `casts_suites.tsv`, `types.tsv` or the docs block.
- "**A pull request stays under 1000 changed lines** of hand-written files - source, plans
  and notes, and hand-curated data such as `census_curated.txt`". The copied curated file
  counts in part a, and the edited lines count twice in part b.
- "**A comment describes the code as it is.**" No "used to", "the old" or "since VARKA-654" in
  a comment; the hook flags them.
- "**Review your own diff before opening the pull request**, with the review the owner runs
  (`/code-review high` on the diff)".
- "**Never `git stash`.**" and "**Never kill by name pattern**".
- "**A red master comes first.**" and "**Check before merging that a pull request cannot turn
  master red.**" Part b commits generated records: if master moved since its last green CI,
  merge master, regenerate and rerun.
- "**A new catalyst suite whose helpers are Java is a JUnit 5 class under `@VarkaSuite`**
  (VARKA-312), each `@Test` carrying its sentence as a `@DisplayName`". The two new tests,
  in existing suites, follow it.

From the repository's `CLAUDE.md`: source lines within 100 characters and ASCII only; the
Apache license header on `VarkaCensusFiles.java`; no push and no pull request without the
owner's approval.

What not to do: do not move or delete a file of `sql/varka/plans/m8/`; do not rewrite a plan of
milestone 8 to cite `m9/`; do not mark any `ltz` cell fused; do not turn `TIMESTAMP`'s
admission; do not add a milestone argument to a reader (each names the current folder, and the
check holds it there); do not edit a generated file to make a check pass; do not weaken or skip
a test that fails.

## 11. Seams with the plans written beside this one

**Row 656.** Its plan is `VARKA-656.md` of `sql/varka/plans/m9/`; its section 12 lists what it
assumes of this row, and its parts' B0 premises grep for each. This row gives every one:

| `VARKA-656.md` 12 assumes | given | under this exact name | part |
| :--- | :--- | :--- | :--- |
| "the registry's `TIMESTAMP` entry has the census name `"ltz"` while its admission is still `Declined`, so `types.tsv` reads `ltz` and `declined`" | yes | `VarkaTypes`: `case TIMESTAMP -> new Info(id, "ltz", "TIMESTAMP", new Admission.Declined(...)`; `sql/varka/types.tsv`, the `TIMESTAMP` line, third field `ltz`, fifth `declined` | b |
| "`VarkaTypeConformance` has `"ltz"` in `CAST_TESTS` and a test `castToTimestamp`" | yes | `CAST_TESTS` holds `"ltz"` after `"int"`; `void castToTimestamp()`, display name "the cast to TIMESTAMP, in LEGACY, ANSI and TRY" | b |
| milestone 9's five files "are in `sql/varka/plans/m9/`, and every reader reads them there" | yes | `sql/varka/plans/m9/census_curated.txt`, `census.tsv`, `casts.tsv`, `casts_fused.tsv`, `casts_suites.tsv` | a |
| "each of the 22 entries of 4.8 admits `ltz` (through `ALL`) with no fused mark; the summary prints a line for `ltz`" | yes | the 21 entries of this plan's row-656 list and `in_set`; each has types `ALL` and no `ltz` in its fused field. The summary's line is `  ltz     new         0 / N`, which its grep for `" ltz "` finds; its `K` is 0 | b |
| "the census holds an entry of class `SubtractTimestamps` whose types hold `ltz`; its name is not known here (`REG`)" | yes | the implicit entry `ntz_minus_ntz`: `ntz_minus_ntz\|SubtractTimestamps\|datetime\|ntz,ltz\|dt\|ntz\|semantics\|339,671\|...`. So its `REG` is `ntz_minus_ntz`, and it reports no finding | b |
| "`dev/varka_census.py` maps the coverage column type `timestamp` to `ltz`" | yes | the line `"timestamp": "ltz",` of `SIMPLE_COLUMN_TYPES`; its part B's step 10 has nothing to add | b |
| "every cast pair of `ltz` is `declined` in the cast table until its row" | yes | the 28 lines of `casts_fused.tsv` read `declined` or `illegal`; the 23 lines of `casts.tsv` have an empty `fused_today` | b |

Command 8 of 5.2 checks the second, fifth and sixth; commands 2, 3 and 4 the others. After
this row `CAST_TESTS` has 14 entries.

What this row does not give row 656, and what row 656 will see that its plan does not say:

- **`656` is already in the `rows` of 21 entries.** Of row 656's 22 entries, this row curates
  21 with `656` in their rows, and `in_set` with `667`, as `m9/PLAN.md` row 667 names it. Row
  656 fuses `in_set` too and adds `656` to that one line's rows; it adds it to no other.
- **The need.** This row sets no need to `done` for an entry that admits `ltz`. Row 656 sets
  an entry's need back to `done` where its fused types then equal its types.
- **The census prints no admission.** `VARKA-656.md` part A turns `Declined` to `Owed("656")`
  and allows for "the census files' lines of `ltz` changed too": none changes. `READ` leaves
  out `owed` as it leaves out `declined`, and the docs' Lanes cell reads `plain` for both.
- **At its part B the count rises by one more than its cells.** Once the admission is `OnLane`,
  the alias `timestamp` counts `ltz` as fused by its identity (4.9): `ltz`'s fused count rises by
  its 22 cells plus 1, to 23 of 70 on the counts of `a0e3a62cf2b`, the docs' Lanes cell for `ltz`
  becomes `LONG`, and the census check then needs the registry's conformance (fact 13), which its
  part B sets (`Conformance.Suite("Timestamp")`).
- **The suites' record.** Once a kernel reads the type, Spark's cast suites lift `TIMESTAMP`
  literals into columns and the record gains lines whose source is `ltz`: row 656 regenerates.
- **The prose that says no kernel reads the type.** Part b writes "and TIMESTAMP, which no
  kernel reads yet" into `README.md` and leaves the counts of the types a kernel reads at
  thirteen in `README.md` and `docs/sql-varka.md`. Row 656 edits that clause and those counts
  when a kernel reads the type.
- **No fused cell, no coverage column, no lane.** They are row 656's.

**Rows 678 and 679.** No seam: neither reads or writes a census or cast file, the registry's
census names or `dev/varka_census.py`.

**Two merged plans,** which this plan keeps as they are written: `VARKA-657-PARTS.md` part C
finds the curated file by `git ls-files 'sql/varka/plans/m*/census_curated.txt'` and expects
three lines "whose second field holds `ltz` and whose fourth does not"; part D expects the pair
`ltz` to `date` "legal in the three modes, not fused". `VARKA-688.md` part c greps
`TimestampAddInterval` in `sql/varka/plans/m9/census_curated.txt`. Command 8 of 5.2 checks both.
After part a, `git ls-files 'sql/varka/plans/m*/census_curated.txt'` prints two paths,
milestone 8's and milestone 9's; row 657 takes milestone 9's, as its text says.

**Deviations from the shared names of the zone family:** none. The row reads and adds no name
of rows 657 and 682 to 692; the one name of theirs it writes is `VarkaZoneSweepSuite`, row
684's suite, in the cast table's proof column.

## 12. Outcome

To be written when the row closes.
