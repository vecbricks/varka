# Adding an expression to Varka

Teaching Varka a new expression is the most common change this engine takes, and
it follows the same path every time. This is that path, in the order the code
runs, with the files named.

Read [the architecture guide](../../docs/sql-varka.md) first, at least its glossary - *lane*,
*word*, *morsel* and *epilogue* all mean something specific here and this page
uses them without further explanation.

## The shape of the change

The checklist below is not a proposal; it is what the last ten expression
additions actually touched, counted from the history. Four files change every
time, and the rest are conditional on what the expression needs.

| | Always | |
| :--- | :--- | :--- |
| `VarkaVectorIR.java` | 10/10 | the node |
| `VarkaLoopEmitter.java` | 10/10 | the code it emits |
| `VarkaEmitter<Family>Suite.scala` | 10/10 | proof the emitted loop matches the reference |
| `m4/PLAN.md` | 10/10 | the task row |

| | Nearly always | |
| :--- | :--- | :--- |
| `VarkaExpressionCompiler.scala` | 9/10 | the Catalyst translation |
| `VarkaExpressionCompilerSuite.scala` | 9/10 | what compiles, and what declines and why |
| `VarkaIrFuzzSuite.scala` | 9/10 | the generator must be able to produce the node |
| `VarkaDifferentialSuite.scala` | 9/10 | end-to-end against the row engine |
| `docs/sql-varka.md` | 9/10 | the supported-expression tables |

| | When it applies | |
| :--- | :--- | :--- |
| `VarkaReferenceEvaluator.scala` | 7/10 | scalar semantics the fuzzer checks against |
| `VarkaEmitterParityBenchmark` + its three results files | 8/10 | a committed op-count and throughput case |
| `VarkaThroughputBenchmark` + its three results files | 6/10 | end-to-end throughput |
| `VarkaChrono.java` | | a scalar twin or a new calendar constant |
| `VarkaDerivedKind.java` and a `*Leaf.java` | | a value the evaluator derives per batch, such as `next_day`'s weekday column |
| `VarkaEmitOptions.java` | | a variant worth measuring both ways before choosing |
| `sql/varka/skills/*.md` | 6/10 | a lesson worth keeping when the work taught one |

Two obligations are newer than that count and so appear in none of the ten:
`VarkaCoverageSuite`, which holds the coverage table's rows and fails if an
expression the compiler admits is undocumented, and the two files it generates
(`docs/sql-varka.md`'s table and `sql/varka/coverage.json`). Step 7 below is where
they are dealt with, and there is no way to skip it quietly.

## The path

Ten numbered steps, each ending in a command that either passes or tells you what
is still missing. Run the check before starting the next step: every one of them
fails loudly and specifically, and the failure is faster to read than the code is.
Steps 8 and 9 are conditional and say so; the rest always apply.

**1. Decide the lowering before writing anything.** Every Varka node computes over
int32 lanes with no branches: the arithmetic must be expressible as adds,
subtracts, multiplies, shifts, compares and blends. If the natural formula needs a
division, look for a magic-multiply form - the calendar code is full of them and
`emitFloorMod7` is the worked example. If it needs a branch, express it as a mask
and a blend. If it can genuinely do neither, the right answer may be to decline the
expression, which is a normal outcome and not a failure.

> *Check:* none - this step is a decision. Write the lowering into the task's plan
> file before step 2, because steps 4 and 6 are checked against it.

**2. Add the IR node** to `VarkaVectorIR`. It is a sealed interface of records, so a
new node is a record plus a `permits` entry. Carry no literal *values* in the node:
a folded constant becomes a literal slot index, because that is what lets two
queries differing only in their constants share one emitted class.

> *Check:* `build/sbt catalyst/Test/compile`. It **must fail**, listing every
> `switch` that does not yet handle the node. That list is the work of steps 3 and
> 4, and it is the intended way to find all of them - do not go looking by hand.

**3. Teach the compiler to build it** in `VarkaExpressionCompiler`. This is the only
file that knows Spark's expression classes. Match the Catalyst tree, compile the
children, and return the node - or decline with a reason, which the driver surfaces
and the tests assert on. Take the decline path seriously: a wrong decline costs
performance, but a wrong *acceptance* costs correctness.

> *Check:* `dev/varka_emit.sh "<your expression>"`. The entry must read
> `FusedOutput`. A `ResidualOutput` line names the reason it declined, in the
> compiler's own words, which is the fastest description of what is still missing.

**4. Emit it** in `VarkaLoopEmitter`. Add the arm to `emitValue`, and to whichever
analysis passes the node participates in - the exhaustiveness errors from step 2
list them. If the node is a value over one date, check whether it can share the
`civil-from-days` prefix rather than recomputing it.

> *Check:* `build/sbt 'catalyst/testOnly *VarkaEmitter*Suite'`.

**5. Say what its nulls do.** A node that is null when any input is null needs
nothing: that is the default. Anything else - a node that can produce a null from
valid inputs, or that is valid where an input is not - has to say so, or the dense
body will be wrong in a way no test of null-free data can catch.

> *Check:* `build/sbt 'catalyst/testOnly *VarkaIrFuzzSuite'`, which runs random
> trees over random null patterns against the reference evaluator. Extend the
> generator so it can produce the new node: the suite asserts it reaches every node
> type, so a node the generator cannot build is a gap it would silently not cover.

**6. Say what its range does**, if it touches the calendar. The decomposition is
exact only inside a bounded day range; a node that can move a date outside it must
either be bounded at compile time or carry a runtime guard on its own result.

> *Check:* `build/sbt 'catalyst/testOnly *VarkaDifferentialSuite'` - the whole
> query against stock Spark's row engine, through both a columnar and a row
> consumer. This is the step that catches a guard that is missing rather than wrong.

**7. Add the expression to the coverage table.** Add a row to `VarkaCoverageSuite`'s
`families` list, in the family it belongs to, with a note if its support is
conditional. Then regenerate:
`VARKA_COVERAGE_REGEN=true build/sbt 'catalyst/testOnly *VarkaCoverageSuite'`,
which rewrites the table in `docs/sql-varka.md` and `sql/varka/coverage.json`.

> *Check:* `build/sbt 'catalyst/testOnly *VarkaCoverageSuite'` with no environment
> variable. Skipping this step does not leave the documentation merely
> out of date - the suite fails, because it asserts that every expression class the
> compiler matches on is exercised by a documented row.

**8. Add a benchmark case,** if the expression is meant to be fast. The project's
rule is that a performance claim traces to a committed results file, so an
expression with no case has no claim. Use `dev/varka_bench_ids.sh` for the next free
case id; the ids must be unique per file and choosing one by eye gets it wrong.

> *Check:* `dev/varka_emit.sh "<your expression>" --table`, whose op counts are what
> the plan or the benchmark entry registers.

**9. Write down what it taught you,** if it taught you something that will outlive
the task: a new `##` section in the right file under `sql/varka/skills/`, especially
a negative result. Regenerate the index with
`dev/varka_toc.py SKILLS.md --from sql/varka/skills`.

> *Check:* `dev/varka_toc.py --check SKILLS.md --from sql/varka/skills`, which the
> pre-commit hook also runs.

**10. Close it out.** Fill in the task plan's outcome section and mark the milestone
row.

> *Check:* `dev/varka_gate.sh`, the standing gate - everything a task plan's
> verification section lists, in order, with a summary table at the end. This is the
> command to run before proposing the change, and a green gate is what "done" means.

## Two things that catch people

**A benchmark case is not optional if the expression is meant to be fast.** The
project's rule is that a performance claim traces to a committed results file, so
an expression with no case has no claim. `dev/varka_bench_ids.sh` gives the next
free case id - the ids must be unique per file, and choosing one by eye gets it
wrong.

**Declining is a feature.** Every stage may refuse: the compiler on an expression
it cannot translate, the emitter on a shape it will not build, the kernel on a
batch whose values leave the range it proved. Each falls back to stock Spark for
that batch and the query still returns the right answer. Write the refusal as
deliberately as the acceptance, and test it.
