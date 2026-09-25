# Task 184: The refactoring tools under `dev/`

*Opened and done 25 September 2026, from `PLAN_MILESTONE_6.md` 9.2's list
after its top five; scope item 46. 2.7 of the milestone schedules it before
task 175's port, whose inventory it generates.*

## 1. The question

Task 159's split was done with three scratch scripts that any later refactor
or port wants, and task 159's plan still named an `emitBoundedDivide` that
never existed, because its member list was written from memory. Only one of
the three survived, a call-graph prototype with task 159's groups written into
it. Task 174, the same day as this one, needed the other two again: its
inventory of members to move was taken from the compiler's error list by hand,
and its unused imports were removed by hand, one report per multi-minute build.
**What does a refactor need, as tools a newcomer can run?**

## 2. The change

Three scripts, each with a doctest suite and `--help`, and a table in
`docs/sql-varka.md` ("Refactoring and porting") saying when to use each:

* `dev/varka_members.py`, the member map. For Java and Scala files it strips
  comments and strings, tracks brace and parenthesis depth, and takes a
  declaration at depth one inside a top-level type as a member, with its line
  range from its doc comment and annotations down to the next member, its doc
  comment's first sentence, and with `--callees` the other listed members it
  names. Given several files, callees are looked up across all of them. Plain
  fields are left out of callees by default, because an instance field is
  usually named like the locals that shadow it; `--fields` puts them back.
* `dev/varka_callgraph.py`, the crossings between groups of that map:
  `--group NAME=REGEX` for a split still to be made, `--by-file` for one
  already made.
* `dev/varka_unused_imports.py`, which reads a build log's unused-import
  reports, scalac's and checkstyle's, and removes all of them in one pass,
  down to the one selector of a brace import, rewrapped at 100 columns. Dry
  run by default.

`PLAN_TASK_TEMPLATE.md`'s section 2 now says a refactor's member list is
generated with the map and its seam chosen with the call graph.

## 3. Verification

* The doctests of all three pass, `ruff check` and `ruff format` pass at the
  pinned version.
* The member map over every Varka Java and Scala source under `sql/catalyst`
  and `sql/core`, 52 files, gives 999 members with no inverted range; its
  largest members are real long methods, where a misread would show as an
  outlier. Two misreads found that way were fixed and are doctests now: a
  constructor with no modifier, and a method's wrapped parameter list read as
  a field.
* The call graph on master, where task 174 has not landed, with the emitter's
  files as groups: the crossings from `Slots` into the facade are the lent
  members task 174 found from the compiler (`BodyMode`, `FragmentKey`,
  `MAX_CHAIN_DEPTH`, the word sentinels, `childrenOf`, `fragmentKey`, `reaches`,
  `referenced`), which is the check that it reports what a split has to move.
* The stripper replayed on copies of the two reports task 174 met, scalac's
  unused selector in `VarkaChronoCompiler.scala` and checkstyle's two unused
  imports in the facade, produces the edits made there by hand, the Scala one
  to the character.

## 4. Outcome

**Done, 25 September 2026.** Task 175's port can start from the map.

## 5. Explicitly out of this task

* A parser. The map reads declarations by pattern and callees as words, which
  over-reports an overloaded name and never misses one; that is the right
  error for an inventory, and a real parser is not worth a dependency here.
* Kotlin, Python or any other language.
