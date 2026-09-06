# Working in `sql/varka`

Instructions for this directory and the Varka code it governs: the engine module
here, the emitter and IR in `sql/catalyst/.../codegen/varka/`, the exec nodes and
rule in `sql/core/.../execution/Varka*`, and `docs/sql-varka.md`.

## Open work is recorded, never left as a marker

**The Varka code carries no `TODO` or `FIXME` comments, and should not start.**
A marker in code has no owner, no reason and no paper trail; six months later
nobody can tell whether it is a plan, a doubt or a leftover. Every piece of known
but unfinished work lives in a plan file instead, where it carries the reason it
was deferred and what has to be true before it starts:

* **Work that belongs to a task in flight** goes in that task's plan file
  (`plans/PLAN_TASK_<n>.md`), under its "explicitly out of" section.
* **Work with no owner yet - the "we should really..." kind** goes in the current
  milestone's **debt register** (`plans/PLAN_MILESTONE_2.md` section 8 is the
  worked example, written during the task-11 audit). One bullet per debt: what it
  is, why it is a debt, and what closing it would take. A debt with a measurement
  attached is worth ten with an adjective.
* **Work for later milestones** goes in the furthest-out scope document -
  currently `plans/SCOPE_MILESTONE_6.md` - which keeps a scope catalogue for
  exactly this. A milestone's catalogue moves forward when its own plan
  becomes a task plan.

When a debt is swept, the register entry is not deleted - it is rewritten in the
past tense with the task that swept it and what the sweep found. The register is
a record, not a queue.

If you are about to type `// TODO`, put it in one of those three places and
reference it from the code only if the code would be confusing without the
pointer.

## Plans are records, not scratch space

`plans/` holds one file per milestone and one per task, and they are written *as
the work happens*: a task file states what was planned, then what was actually
built, what was measured and where the plan turned out wrong. Predictions written
before a measurement stay in the file after it, scored honestly - the project
keeps count. Do not retro-edit a task file to look prescient; add the correction
and say what it corrects.

A milestone's file is named for the stage it is in. While it is a scope
catalogue - numbered items with design input, no task numbers assigned yet - it
is `SCOPE_MILESTONE_<n>.md`; when it becomes a task plan with a numbered task
table it is `PLAN_MILESTONE_<n>.md`. Milestone 3's file says in its own opening
paragraph that it "is no longer the scope document it opened as", which is
exactly the transition the two names make visible from the outside.

A milestone's plan may only be renamed or renumbered if every inbound reference
is updated with it. Other plans cite sections and items by number.

## Measurements, not adjectives

Any performance claim in code comments, plans or docs traces to a committed
benchmark result file. The methodology is fixed (`PLAN_TASK_14.md` 2.1): at least
five iterations over two-second windows, generated on an otherwise idle machine,
and any claim resting on a ratio under ~1.3x is re-run and compared by minimums
before it is written down. When a change moves committed numbers, regenerate the
affected results files in one run rather than patching quoted figures case by
case, and requote the docs from that run.

The tooling for that lives in `dev/`. `dev/varka_bench_regen.sh <module> <Class>`
regenerates one benchmark at both vector widths, refuses a busy machine, writes
the narrow run to a committed `-128bit-results.txt` companion and a provenance
sidecar (commit, JDK, kernel, CPU, load), and ends by diffing the wide file
against the committed one. `dev/varka_bench_diff.py` is that diff on its own:
before/after between two files or against a git revision, or A/B pairs inside
one file (`--within FILE --ab "label A" "label B"`), controls listed first.
Read the controls before anything else: a run whose scalar anchors are flat but
whose earliest sections all dropped was started on a machine still hot from a
test suite, and its numbers are the machine's, not the code's.

Two more guards. `dev/varka_bench_canary.sh` runs three fixed loops - a
compute-bound control, a cache-resident vector add, a memory-bound one - and
compares them with the committed baseline for this host; the regen script runs
it first and stops when the machine is not in its baseline state, because the
same code has measured its memory-bound kernels 20-27% apart on different days
with every compute-bound row flat. And `dev/varka_quote_check.py` checks that
every number with decimals quoted in the plans, `SKILLS.md`, the docs and the
README appears in a committed results file, now or in its history; the ones
that predate the tool are in `dev/varka_quote_allowlist.txt` with a reason
each, and that list only shrinks. It is a step of the gate.

Before committing, `dev/varka_precommit.sh` (or install it once with
`--install-hook`) checks the staged files for the rules that slip most: a
non-ASCII byte outside a string literal, a source line over 100 columns, a
`TODO`/`FIXME` marker under a Varka directory, the quote check when a
document changed, and `ruff check` plus `ruff format --check` on Python files
(CI's Python linter runs both). And beside the emitter suite's curated matrices,
`VarkaIrFuzzSuite` runs random IR trees over random columns, null patterns,
lengths and `VarkaEmitOptions` variants against the shared
`VarkaReferenceEvaluator`; a failure names its seed and iteration and replays
with `-Dvarka.fuzz.seed=<seed> -Dvarka.fuzz.only=<iteration>`. A new option is
fuzzed the day it lands, since the suite toggles every `with*` on the record.

A regeneration ends with the requote: `dev/varka_bench_diff.py --git HEAD <file>
--requote` lists, under each moved row, every document line that quotes its old
number, and the regeneration is done when that list is empty or every line left
says on purpose that it quotes the number a change moved away from. The checks
that need volume run from `dev/varka_nightly.sh` - the canary, the fuzzer at ten
thousand iterations with the day's seed, the exhaustive sweeps, optionally the
gate - into a dated log under `target/varka-nightly/`.

A task starts with `dev/varka_task_new.sh <n> "<title>"`: the worktree and
branch off master, the plan file from `plans/TEMPLATE_TASK.md` (the sections
above, with guidance under each that is deleted as they are filled), and the
pre-commit hook installed. The first commit is the plan, once its admission
check is done.

For an emitter question, `dev/varka_emit.sh "year(d)" "month(d)"` prints what
a projection compiles to, its shape hash, and per emitted method the bytecode
size and the `IntVector`/`VectorMask` invocation counts on the scale the
emitter suite's op-count tests use - so a plan's registered op counts come
from the tool, not from reading the emitter. `--asm` adds C2's assembly for the
dense loop and its mnemonic frequencies; `--options k=v` selects any
`VarkaEmitOptions` variant by name. `--table --variant k=v` prints the
op-count table a plan registers instead - one row per expression, one column
per variant, deltas against the defaults - ready to paste.

`-XX:+PrintAssembly`, and so `--asm` and the assembly suite, need the hsdis
disassembler plugin, which no JDK ships. `dev/varka_hsdis_build.sh` builds it
in seconds from the JDK's one source file and the distribution's capstone
library, without a JDK build, and checks HotSpot loads it. Worktrees are
listed and the merged ones removed with `dev/varka_worktree.sh list|gc`.

`SKILLS.md` at the repository root is where a lesson goes when it will outlive
the task that learned it - especially the negative results, which are what stop
the next person re-litigating a settled question.

## Papers are transcribed, not paraphrased from memory

`papers/` holds machine transcriptions of the third-party papers this work reads
closely - currently one, Neri and Schneider's calendar algorithms. They are there
so a plan can cite a constant, a theorem number or a range bound with the
argument at hand. Two rules follow from what they are. They carry their own
authors and licence and are **not** this project's documentation, so nothing in
`papers/` is Apache-licensed, compiled or shipped. And they are lossy - a PDF's
typography carries part of every formula - so **when a constant, an exponent or
an inequality decides something, read the PDF, not the transcription**. Each file
opens with what its conversion lost. See `papers/README.md` before adding one.

## House rules that bite here specifically

* Java in a non-core module cannot pass a Guava type to a `core` API. Maven shades
  `core`, relocating `com.google.common` to `org.sparkproject.guava`, so a signature
  like `NonFateSharingCache(Cache)` arrives at javac with the relocated parameter
  type. Scala is unaffected - scalac reads the Scala pickle, which shading does not
  rewrite - and SBT does not shade at all, so only the Maven CI job catches it. Keep
  Guava inside the module that owns it and reimplement the few lines locally when a
  `core` utility cannot be reached without one; `SKILLS.md` records the mechanism and
  the seconds-long local check. The same applies in reverse to Scala: a Scala call
  site passing a Guava type compiles but names a method the shaded artifact does not
  have.
* A Java class holding an incubator-module type (`jdk.incubator.vector`) in a field
  needs `--add-modules` in **two** places under Maven: `scala-maven-plugin`'s
  `javacArgs`, which compiles it, and that plugin's `jvmArgs`, because zinc extracts
  the class's API afterwards by calling `Class.getDeclaredFields()` reflectively in the
  compiler's own JVM. Missing the second gives `NoClassDefFoundError` *after* a
  successful compile, and only the Maven CI job sees it - the sbt launcher already runs
  with the module resolved. `SKILLS.md` records the mechanism and a seconds-long local
  check. The engine module needs neither block because it has no parent pom and uses
  `maven-compiler-plugin` directly.
* Scala cannot see `java.lang.classfile` types: Scala 2.13's typechecker reports
  "illegal cyclic reference" when completing them, and the Maven build's scaladoc
  pass fails on it. Emitter-adjacent code that touches the Class-File API stays in
  Java, with no class-file type in any signature and fully-qualified names inside
  method bodies; Scala reaches it through plain `byte[] -> String` shims.
  `build/sbt catalyst/doc` is the local gate that reproduces the CI failure.
* **The project is migrating from Scala to modern Java (25+): prefer Java for new
  Varka code wherever possible.** Records, sealed interfaces and pattern-matching
  switches are already the house style in the IR and emitter; match it. Reach for
  Scala only at surfaces that force it - `SparkPlan` subclasses, the Catalyst rule
  and expression matching, ScalaTest suites. Existing Scala is not rewritten as a
  side effect of another task; the migration is its own work (milestone 3,
  task 23).
* Every hot loop method stays small by construction (`GROUP_BUDGET`): C2's compile
  time grows steeply with vector-op count, and a wide method is slower to compile
  *and* slower to run. Sibling methods, not longer methods. Two recorded exceptions:
  the budget partitions *between* outputs and never splits inside one, so a single
  output wider than the budget still forms one method - the capped `IN` chain
  (task 20, up to 33 ops in the benchmarked shape) is the known case, its ~30 ms
  one-time C2 compile per fresh shape accepted because the task-18 class cache
  amortizes it and the measured win at the cap is 4.0x; and calendar outputs over
  one date share a method up to `FUSED_CEILING` (400 ops), because the
  shared civil-from-days prefix makes the wider method less work rather than more
  (task 32, 2.15x on four fields). Nothing else widens a method: two plain
  chains over a shared subchain stay split, and whether they still should is task
  43's open question (task 17 measured the merge as a loss; the parity file has read
  it as a win since task 46 moved the validity OR ahead of the vector work).
* The ghost fallback is a correctness contract: a Varka failure degrades to the
  row engine and never fails a query. Anything that can return a *wrong* answer
  rather than fail - a cache key, for one - gets its own differential coverage,
  because the fallback cannot catch it.
