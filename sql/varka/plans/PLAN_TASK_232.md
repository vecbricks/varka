# Task 232: A projection with a nondeterministic entry runs as vanilla's

*Opened and done 27 September 2026 (milestone 6 row 232, found by task 230's check of its risks,
`PLAN_TASK_230.md` 11).*

## 1. The bug

`SELECT add_months(d, 1), rand(7)` over an Arrow-cached table planned a Varka node: the
compiler fused `add_months` and left `rand` a residual entry. The node evaluates residual
entries row by row through projections of its own - the row path's, and beside the kernel the
merge-at-row projection and the residual columns - and none is initialized with the partition
index, as Spark's `ProjectExec` initializes its own. The query failed with
`NullPointerException: Cannot invoke "java.util.Random.nextDouble()"`, on both nodes, with no
kernel and on the default warm-up path.

## 2. The fix, and the one not taken

A projection with any nondeterministic entry declines as a whole, in `classify`, with the
reason on every entry for `EXPLAIN`: the way the compiler already declines a filter whose
condition has a nondeterministic conjunct. The query then runs as vanilla's, and gives
vanilla's values.

Initializing the node's projections with the partition index would make the query run, but not
give vanilla's values: a node with a kernel evaluates residual entries on its row path for some
batches and beside the kernel for others, so each path would need a generator, and a row's
value would depend on the path its batch took, where vanilla draws every row of a partition from
one. Declining costs the fused siblings of such an entry their kernel, which a projection that
also asks for random numbers can spare.

## 3. Tests

1. `VarkaExpressionCompilerSuite`, "a projection with a nondeterministic entry declines whole,
   with the reason on each entry": the classification.
2. `VarkaDifferentialSuite`, "a projection with a nondeterministic entry runs as vanilla's, to
   rows and to batches": the reproducer, against the row engine's answers and through the noop
   sink. Without the fix it fails with the `NullPointerException` above.
3. CI's Varka suites of `catalyst` and `sql`, since every projection passes the new check.
