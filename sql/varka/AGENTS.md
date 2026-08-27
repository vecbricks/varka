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
* **Work for later milestones** goes in the next milestone's plan. While
  milestone 3 is in flight that is `plans/SCOPE_MILESTONE_4.md`, which keeps a
  scope catalogue for exactly this; a milestone's own catalogue moves forward
  with it when its plan becomes a task plan.

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

`SKILLS.md` at the repository root is where a lesson goes when it will outlive
the task that learned it - especially the negative results, which are what stop
the next person re-litigating a settled question.

## House rules that bite here specifically

* Scala cannot see `java.lang.classfile` types: Scala 2.13's typechecker reports
  "illegal cyclic reference" when completing them, and the Maven build's scaladoc
  pass fails on it. Emitter-adjacent code that touches the Class-File API stays in
  Java, with no class-file type in any signature and fully-qualified names inside
  method bodies; Scala reaches it through plain `byte[] -> String` shims.
  `build/sbt catalyst/doc` is the local gate that reproduces the CI failure.
* Every hot loop method stays small by construction (`GROUP_BUDGET`): C2's compile
  time grows steeply with vector-op count, and a wide method is slower to compile
  *and* slower to run. Sibling methods, not longer methods.
* The ghost fallback is a correctness contract: a Varka failure degrades to the
  row engine and never fails a query. Anything that can return a *wrong* answer
  rather than fail - a cache key, for one - gets its own differential coverage,
  because the fallback cannot catch it.
